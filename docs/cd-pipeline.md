# CD 파이프라인 (GitHub Actions → EC2)

이 문서 하나로 **ZIPICK 자동 배포가 무엇을 하고, 어떤 순서로 돌고, 무엇을 건드리면 안 되는지** 알 수 있게 썼습니다.

- 처음 읽는 분은 **1~4장**만 봐도 전체 그림이 잡힙니다.
- **AWS 담당자**는 **6장**에 필요한 설정·권한·주의사항이 모두 모여 있습니다.
- 실제로 배포·롤백하는 분은 **7~9장**을 보세요.

---

## 1. CD 가 무엇을 대신해 주나

전에는 배포 담당자가 자기 노트북에서 이 과정을 직접 했습니다.

```
이미지 3개 빌드(ARM64) → SSH 로 EC2 접속 → 이미지 전송 → docker compose 로 교체 → 눈으로 확인
```

이걸 **GitHub 웹에서 버튼 한 번**으로 바꾼 것이 CD 입니다. 목적은 세 가지입니다.

| 목적 | 설명 |
| --- | --- |
| **사람 실수 줄이기** | 명령어를 손으로 치지 않습니다. 순서·옵션이 항상 같습니다 |
| **누가 눌러도 같은 결과** | 특정 사람의 노트북·SSH 키에 의존하지 않습니다 |
| **되돌릴 수 있게** | 어떤 코드가 올라갔는지 기록이 남고, 이전 버전으로 돌아갈 수 있습니다 |

### 자동이 아닙니다 — 버튼을 눌러야 돕니다

`dev` 에 merge 한다고 배포되지 않습니다. **Actions 탭에서 직접 실행**해야 합니다(`workflow_dispatch`).
EC2 를 비용 때문에 필요할 때만 켜는 구조라 자동 배포는 적합하지 않습니다.

기존 수동 배포(`deploy/deploy.sh`)도 **그대로 남겨 뒀습니다.** CD 가 고장 났을 때의 복구 수단입니다.

---

## 2. 전체 그림

```
   ┌─────────────────── GitHub (클라우드) ───────────────────┐
   │  Actions 탭 → Deploy → Run workflow                      │
   │       ├─ ① verify  : 배포해도 되는 커밋인가?             │
   │       ├─ ② build   : ARM64 이미지 3개를 만들어            │
   │       │              GHCR(이미지 창고)에 올린다           │
   │       └─ ③ deploy  : AWS 에 "이 명령 좀 실행해줘" 요청     │
   └──────────────────────────┬───────────────────────────────┘
                              │  AWS SSM Run Command
                              │  (SSH 아님 — 서버 포트를 열지 않는다)
                              ▼
   ┌─────────────────── AWS EC2 (t4g.small) ──────────────────┐
   │  remote-deploy.sh 가 서버 안에서 실행된다                 │
   │       → GHCR 에서 이미지 내려받기                         │
   │       → docker compose 로 앱 컨테이너만 교체              │
   │       → 정상 동작하는지 확인                              │
   │                                                           │
   │  컨테이너 5개: db · rabbitmq · backend · notification · web │
   └───────────────────────────────────────────────────────────┘
```

### 등장인물 4명

| 이름 | 역할 | 비유 |
| --- | --- | --- |
| **GitHub Actions** | 빌드하고 명령을 내리는 곳 | 공장 + 관제실 |
| **GHCR** (GitHub Container Registry) | 만든 이미지를 보관하는 창고 | 물류창고 |
| **AWS SSM** | 서버에 명령을 전달하는 통로 | 사내 메신저 (문을 열 필요가 없음) |
| **EC2** | 실제 서비스가 도는 서버 | 매장 |

> **"이미지"가 뭔가요?** 앱 실행에 필요한 것(자바 런타임, jar 파일 등)을 통째로 묶은 **상자**입니다.
> 서버는 상자를 받아 열기만 하면 되므로 서버에서 빌드할 필요가 없습니다.
> EC2 가 RAM 2GB 뿐이라 서버에서 빌드하면 운영 중인 서비스가 느려집니다. 그래서 GitHub 에서 만듭니다.

---

## 3. 배포 한 번의 흐름

### GitHub 쪽 (`.github/workflows/deploy.yml`)

```
① verify — 배포 대상 검증       ← 여기서 막히면 이미지도 만들지 않는다
   · dev 브랜치인가?
   · AWS 연동 Secrets 가 설정돼 있는가?
   · (새 배포) 이 커밋에서 Backend CI · Frontend CI 가 성공했는가?
   · (롤백)   그 태그·커밋·이미지가 실제로 있는가?

② build — 이미지 만들기         ← 롤백일 때는 건너뛴다
   · ubuntu-24.04-arm 러너에서 linux/arm64 네이티브 빌드
   · zipick-backend · zipick-web · zipick-notification 3개
   · 태그는 sha-<커밋 7자리> (예: sha-a1b2c3d)
   · 이미지마다 최근 5개 버전만 남기고 정리

③ deploy — 서버에 전달
   · GHCR 이미지 3개를 받을 수 있는지 먼저 확인
   · OIDC 로 AWS 임시 자격 증명 발급
   · GHCR 임시 토큰을 암호화 파라미터로 저장
   · SSM 으로 remote-deploy.sh 를 서버에서 실행
   · 끝나면 토큰 파라미터 삭제 (성공·실패·취소 무관)
```

### 서버 쪽 (`deploy/remote-deploy.sh`) — 7단계

```
1/7  운영 DB 볼륨 확인     없으면 아무것도 하지 않고 중단
2/7  compose 파일 갱신     배포 커밋에서 받아 검증 통과 후에만 교체
3/7  배포 범위 확인        서비스가 정확히 5개인지 확인
3-1  알림센터 DB 확인      없으면 앱을 바꾸기 전에 중단
4/7  이미지 내려받기       GHCR 로그인 → pull 3개 → 즉시 로그아웃
5/7  서비스 교체           db·rabbitmq 는 그대로, 앱 3개만 교체
6/7  헬스 체크             web → backend → db 경로를 실제 요청으로 확인
7/7  알림센터 헬스 체크    notification 컨테이너가 healthy 인지 확인
```

**1~4단계는 전부 "확인만 하고 아무것도 바꾸지 않는" 단계입니다.**
실제로 서비스가 바뀌는 건 5단계부터입니다. 문제가 있으면 그 전에 멈춥니다.

---

## 4. 설계에서 중요하게 둔 규칙

> 이 장이 이 문서에서 가장 중요합니다. **코드를 고칠 때 이 규칙들을 깨지 않도록** 해주세요.

### 규칙 1 — 운영 DB 는 어떤 경우에도 건드리지 않는다

PostgreSQL 에 실제 사용자 계정과 수집한 공고 데이터가 들어 있습니다. 배포 때문에 날아가면 복구할 수 없습니다.

| 지키는 방법 | 이유 |
| --- | --- |
| `docker compose down` 계열 **사용 금지** | `down -v` 는 볼륨을 지웁니다. 실수 여지를 없애려고 `down` 자체를 안 씁니다 |
| `db`·`rabbitmq` 는 `up -d --no-recreate` 로만 | 없으면 시작만 하고, 떠 있으면 **건드리지 않습니다** (DB 데이터·큐에 남은 메시지 보존) |
| 앱 교체는 `--no-deps` | 앱을 바꿀 때 db·rabbitmq 가 같이 재시작되지 않습니다 |
| 볼륨(`zipick_db-data`)이 없으면 **중단** | 볼륨이 없다 = 첫 설치거나 설정이 어긋난 것. 그냥 진행하면 **빈 DB 가 새로 생깁니다** |
| DDL·초기화 SQL **자동 실행 금지** | 스키마 변경은 사람이 확인하고 수동으로 반영합니다 |

> ⚠️ `docker-compose.prod.yml` 의 db 에는 `./db:/docker-entrypoint-initdb.d:ro` 마운트가 있습니다
> (수동 설치 때 첫 스키마를 만들기 위한 것입니다). 이 스크립트들은 **데이터 디렉터리가 비어 있을 때만**
> 실행되므로 운영 DB 에서는 돌지 않습니다. 다만 그 전제를 지켜 주는 것이 **"볼륨 없으면 중단"과 `--no-recreate`** 이므로,
> **이 둘을 빼면 초기화가 실제로 일어날 수 있습니다.**

### 규칙 2 — 위험한 일 전에 먼저 확인하고 멈춘다

서비스를 교체하다 중간에 실패하면 복구가 어렵습니다. 그래서 **바꾸기 전에 확인할 수 있는 건 전부 먼저 확인**합니다.

- 이미지를 못 받으면 → 컨테이너를 건드리기 **전에** 중단
- `.env` 가 없으면 → 중단 (빈 값으로 컨테이너가 뜨는 것 방지)
- compose 가 현재 `.env` 로 해석되지 않으면 → **기존 파일을 그대로 두고** 중단
- 알림센터 DB 가 없으면 → 앱을 바꾸기 **전에** 중단

### 규칙 3 — 서버의 비밀값은 서버에만 둔다

`DB_PASSWORD`, `JWT_SECRET`, 카카오·마이홈 API 키 등은 **EC2 의 `/home/ubuntu/zipick/.env` 에만** 있습니다.
CD 는 이 파일을 **읽지도 쓰지도** 않습니다 (예외: 헬스 체크 주소를 만들기 위한 `HTTP_PORT` 하나).
비밀값이 GitHub·CI 로그·SSM 기록을 거치지 않으므로 유출될 경로가 없습니다.

> **예외가 하나 있습니다** — GHCR 이미지를 받기 위한 임시 토큰. 처리 방식은 6-3 에 있습니다.

### 규칙 4 — 무엇이 올라갔는지 항상 추적할 수 있게

- 이미지 태그는 `latest` 가 아니라 **`sha-<커밋 7자리>`** 입니다. 이미지 ↔ 커밋이 1:1 로 대응됩니다.
- compose 파일도 **그 이미지를 만든 커밋에서** 받아 옵니다. 이미지와 설정이 어긋나지 않습니다.
- 테스트가 깨진 커밋은 배포되지 않습니다 (CI 성공 여부 확인).

### 규칙 5 — 서버의 문(포트)을 새로 열지 않는다

SSH(22번)는 **배포 담당자 IP 에서만** 열려 있습니다. GitHub Actions 러너는 IP 가 고정되지 않아
SSH 로 접속하려면 넓은 대역을 열어야 합니다. 그래서 **SSM 을 씁니다 — 인바운드 포트를 하나도 열지 않습니다.**

### 규칙 6 — 되돌릴 수 있게

이전 이미지 태그로 다시 실행하면 그 버전으로 돌아갑니다. 단, **DB 스키마는 되돌아가지 않습니다**(8장).

---

## 5. 배포 범위 — 서비스 5개

| 서비스 | 내용 | 배포 때 |
| --- | --- | --- |
| `db` | PostgreSQL 16 | **재생성 안 함** (데이터 보존) |
| `rabbitmq` | 알림 요청 큐 | **재생성 안 함** (큐에 남은 메시지 보존) |
| `backend` | Spring Boot 메인 서버 | 교체 |
| `notification` | Go 알림센터 | 교체 |
| `web` | Caddy (프론트 정적 파일 + `/api` 프록시) | 교체 |

compose 는 **`deploy/docker-compose.prod.yml` 하나**이고, 수동 배포(`deploy.sh`)와 같은 파일을 씁니다.

| 항목 | 값 |
| --- | --- |
| 빌드하는 이미지 | `zipick-backend`, `zipick-web`, `zipick-notification` |
| 서버 `.env` 필수 추가 항목 | `NOTIFICATION_INTERNAL_KEY`, `RABBITMQ_USER`, `RABBITMQ_PASSWORD` |
| DB 사전 작업 | `zipick_notification` 데이터베이스 + `zipick-notification/db/init.sql` (수동, 한 번) |

### 메모리 (t4g.small 1.8GB, 실측)

| 서비스 | 사용량 |
| --- | --- |
| backend | 약 430MB |
| rabbitmq | 약 110MB (한도 256MB) |
| db | 약 70MB |
| web | 약 10MB |
| notification | 약 6MB |

컨테이너를 더 늘릴 때는 다시 확인해야 합니다.

> **참고 (경위)** — 처음 CD 를 만들 때는 알림센터·RabbitMQ 가 개발 중이라 `db`·`backend`·`web` 만 다루는
> 1차 구성(`docker-compose.phase1.yml`)을 따로 뒀습니다. 그 뒤 알림센터(#18)와 큐(#20)가 `dev` 에 머지되고
> 서버에도 배포되어, **CD 를 서버와 같은 전체 구성으로 맞추고 1차 파일은 삭제**했습니다.
> 1차 구성으로 배포하면 백엔드의 큐 설정이 꺼지고 알림 설정 화면이 503 이 되며,
> `rabbitmq`·`notification` 컨테이너가 고아로 남기 때문입니다.

---

## 6. AWS 담당자가 알아야 할 것

> 이 장만 보셔도 됩니다. **CD 가 AWS 를 어떻게 쓰는지, 무엇을 설정해야 하는지**가 모두 있습니다.

### 6-1. CD 가 AWS 에서 쓰는 것은 두 가지뿐

| 쓰는 것 | 무엇에 | 왜 |
| --- | --- | --- |
| **SSM Run Command** | 서버에 배포 스크립트를 보내 실행 | SSH 포트를 열지 않기 위해 |
| **SSM Parameter Store** | GHCR 임시 토큰을 잠깐 맡겨 둠 | 토큰이 명령 기록에 남지 않게 하려고 |

EC2 인스턴스 자체는 CD 가 만들거나 바꾸지 않습니다. **이미 떠 있는 서버에 명령만 보냅니다.**
Parameter Store(Standard)·SSM Run Command 는 무료이고 KMS 요청도 월 2만 건까지 무료라 **추가 AWS 비용은 없습니다.**

### 6-2. 인증 — 장기 키를 저장하지 않습니다

GitHub Secrets 에 AWS 액세스 키를 넣지 않습니다. 대신 **OIDC** 를 씁니다.

```
GitHub Actions 실행 → GitHub 이 "이건 진짜 우리 저장소의 dev 브랜치 작업" 이라는
                      서명된 증명서를 발급 → AWS 가 확인하고 임시 자격 증명을 내어 줌
                      (몇 분 뒤 자동 만료)
```

신뢰 정책에서 **저장소와 브랜치를 반드시 제한**해야 합니다. 제한하지 않으면 다른 저장소도 이 역할을 쓸 수 있습니다.

### 6-3. 비공개 이미지 받기 — 토큰을 어떻게 넘기나

GHCR 패키지는 **조직 관리자 정책으로 공개 전환이 막혀 있어 비공개**입니다. 그래서 EC2 는 배포할 때마다 로그인해서 받습니다.

**쓰는 토큰은 `GITHUB_TOKEN`** — Actions 가 실행할 때마다 자동 발급하는 임시 토큰입니다.
권한은 **이 저장소 패키지 읽기뿐**(`packages: read`)이고, 워크플로가 끝나면 GitHub 이 만료시킵니다. 사람이 만들거나 보관하지 않습니다.

토큰이 SSM 명령 기록이나 Actions 로그에 직접 남지 않도록 다음 순서를 씁니다.

```
Actions                                   AWS                                EC2
① GITHUB_TOKEN 을 SecureString 으로 저장 ─▶ Parameter Store (KMS 암호화)
② SSM 명령에는 "파라미터 이름"만 ─────────────────────────────────────▶ ③ 이름으로 꺼내 복호화
                                                                      ④ 임시 DOCKER_CONFIG 에 login → pull → logout → 폴더 삭제
⑤ 성공·실패와 관계없이 파라미터 삭제
```

| 노출 지점 | 남는 것 |
| --- | --- |
| SSM 명령 기록 | 파라미터 **이름**(`/zipick/deploy/ghcr-token-<실행번호>`)뿐 |
| Actions 로그 (저장소가 Public 이라 로그도 공개) | 토큰을 출력하지 않음. `GITHUB_TOKEN` 은 GitHub 이 자동 마스킹. 값은 명령줄 인자가 아니라 파일로 전달 |
| CloudTrail | SecureString 값은 기록되지 않음 |
| 서버 | 이번 실행 전용 임시 폴더에만 로그인 정보를 쓰고 pull 직후 삭제 (`~/.docker` 에 남지 않음) |

- **약점:** `AmazonSSMManagedInstanceCore` 는 계정 안의 파라미터를 읽을 수 있어, 공용 계정의 다른 서버도
  파라미터가 있는 몇 분 동안 이론상 읽을 수 있습니다. 파라미터는 배포 직후 지우고 토큰도 곧 만료되므로
  위험이 작다고 판단했습니다. 더 줄이려면 인스턴스 역할을 필요한 권한만 적은 정책으로 바꿉니다.
- 비공개 패키지는 조직의 GitHub Packages 용량에 잡히므로, 빌드할 때 **이미지마다 최근 5개 버전만** 남기고 지웁니다.
  **롤백도 이 5개 안에서만 가능합니다.**
- 토큰 없이 받는 대안은 **ECR 이전**입니다(EC2 가 IAM 역할로 pull). 레지스트리를 바꾸는 큰 변경이라 지금은 쓰지 않습니다.

### 6-4. 필요한 AWS 설정

#### ① GitHub OIDC Provider (계정당 한 번)

| 항목 | 값 |
| --- | --- |
| Provider URL | `https://token.actions.githubusercontent.com` |
| Audience | `sts.amazonaws.com` |

#### ② GitHub Actions 가 맡을 IAM 역할

**신뢰 정책** — 저장소·브랜치 제한이 핵심입니다.

```json
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Principal": { "Federated": "arn:aws:iam::<ACCOUNT_ID>:oidc-provider/token.actions.githubusercontent.com" },
    "Action": "sts:AssumeRoleWithWebIdentity",
    "Condition": {
      "StringEquals": { "token.actions.githubusercontent.com:aud": "sts.amazonaws.com" },
      "StringLike":   { "token.actions.githubusercontent.com:sub": "repo:prgrms-be-devcourse/NBE11-13-final-Team01:ref:refs/heads/dev" }
    }
  }]
}
```

**권한 정책** — 대상 인스턴스와 파라미터 경로로 좁힙니다.

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": "ssm:SendCommand",
      "Resource": [
        "arn:aws:ec2:<REGION>:<ACCOUNT_ID>:instance/<INSTANCE_ID>",
        "arn:aws:ssm:<REGION>::document/AWS-RunShellScript"
      ]
    },
    {
      "Effect": "Allow",
      "Action": ["ssm:GetCommandInvocation", "ssm:DescribeInstanceInformation"],
      "Resource": "*"
    },
    {
      "Effect": "Allow",
      "Action": ["ssm:PutParameter", "ssm:DeleteParameter", "ssm:AddTagsToResource"],
      "Resource": "arn:aws:ssm:<REGION>:<ACCOUNT_ID>:parameter/zipick/deploy/*"
    }
  ]
}
```

> 마지막 문장은 비공개 이미지를 받기 위한 임시 토큰 파라미터용입니다. `/zipick/deploy/` 경로로만 제한합니다.
> `ssm:DescribeInstanceInformation` 은 리소스 단위 제한을 지원하지 않아 `*` 가 필요합니다.
> 읽기 전용이며, 배포 전에 "EC2 가 켜져 있고 SSM 에 연결됐는지" 확인하는 데만 씁니다.

#### ③ EC2 인스턴스 쪽

- **IAM 인스턴스 프로파일** 연결 — 관리형 정책 `AmazonSSMManagedInstanceCore`
- **SSM Agent 실행 확인** — Ubuntu 공식 AMI 에는 snap 으로 설치돼 있습니다.
  ```bash
  sudo snap services amazon-ssm-agent
  ```
- SSM 엔드포인트로 **아웃바운드 HTTPS(443)** 허용
- **AWS CLI 설치** (한 번) — 서버가 Parameter Store 에서 임시 토큰을 꺼낼 때 씁니다.
  ```bash
  sudo snap install aws-cli --classic
  ```
  인스턴스 역할의 `AmazonSSMManagedInstanceCore` 에 파라미터 읽기 권한이 들어 있고,
  AWS 관리형 키(`aws/ssm`)로 암호화하므로 별도 권한은 보통 필요 없습니다.
- **인바운드 규칙은 바꾸지 않습니다.** SSH 22번은 지금처럼 담당자 IP 만 유지하세요.

### 6-5. GitHub Secrets / Variables

**저장소가 Public 이라 Actions 로그도 공개됩니다.** AWS 계정 ID·인스턴스 ID 가 로그에 남지 않도록
Variables 가 아니라 **Secrets** 에 넣습니다.

| Secrets | 용도 |
| --- | --- |
| `AWS_ROLE_ARN` | 위 ②에서 만든 IAM 역할 ARN (계정 ID 포함) |
| `EC2_INSTANCE_ID` | 배포 대상 인스턴스 ID (`i-0abc...`) |
| `VITE_KAKAO_MAP_KEY` | 카카오 JavaScript 키 — 프론트 **이미지 빌드 시점**에 필요 |

| Variables (선택) | 기본값 |
| --- | --- |
| `AWS_REGION` | `ap-northeast-2` |
| `APP_DIR` | `/home/ubuntu/zipick` |

> `VITE_KAKAO_MAP_KEY` 는 원래 브라우저에 노출되는 공개 키입니다. 저장소에 평문으로 두지 않으려고
> Secrets 를 쓰는 것이고, 실제 보호는 **카카오 콘솔의 도메인 등록**으로 합니다.

또한 GHCR 패키지 3개(`zipick-backend`, `zipick-web`, `zipick-notification`) 각각의
**Package settings → Manage Actions access** 에 이 저장소가 Read 이상으로 연결돼 있어야 합니다.
하나라도 빠지면 배포 job 이 서버에 들어가기 전에 멈추고 안내를 출력합니다.

### 6-6. AWS 담당자가 바꿀 때 주의할 점

- **인바운드 보안 그룹을 열지 마세요.** CD 는 포트가 필요 없습니다. 열면 규칙 5 가 깨집니다.
- **IAM 권한을 넓히지 마세요.** 특히 `ssm:SendCommand` 를 `Resource: "*"` 로 두면
  계정의 **모든 인스턴스에 임의 명령을 실행**할 수 있게 됩니다.
- **파라미터 경로(`/zipick/deploy/*`)를 바꾸면** 워크플로의 이름 규칙도 같이 바꿔야 합니다.
- **EC2 를 교체하면** `EC2_INSTANCE_ID` Secret 과 IAM 정책의 인스턴스 ARN 을 **모두** 갱신해야 합니다.

---

## 7. 서버에 미리 준비돼 있어야 하는 것

CD 는 아래를 **만들지 않습니다.** 없으면 확인 단계에서 멈춥니다.

| 항목 | 확인 방법 | 없으면 |
| --- | --- | --- |
| `/home/ubuntu/zipick/.env` | `ls ~/zipick/.env` | 중단. `.env.prod.example` 참고해 작성 |
| `zipick_db-data` 볼륨 | `docker volume ls` | 중단. 첫 설치는 `deploy/deploy.sh` 로 |
| `zipick_notification` DB + 테이블 | 아래 절차 | 중단 |
| AWS CLI | `which aws` | 중단. `sudo snap install aws-cli --classic` |

### `.env` 필수 항목

기존 값에 더해 알림센터·큐 항목이 필요합니다. 없으면 compose 해석 단계에서 멈추고 **기존 컨테이너는 그대로 둡니다.**

```
NOTIFICATION_INTERNAL_KEY=   # 메인 서버 ↔ 알림센터 공유 키
RABBITMQ_USER=
RABBITMQ_PASSWORD=
```

운영 비밀값(`DB_PASSWORD`, `JWT_SECRET`, `MYHOME_API_KEY`, `KAKAO_REST_API_KEY` 등)은 이 파일에만 둡니다.

### 알림센터 DB (최초 한 번, 수동)

**CD 는 운영 DB 에 DDL 을 실행하지 않습니다.** 사람이 한 번 만들어야 합니다.

```bash
cd ~/zipick
docker compose -f docker-compose.prod.yml exec -T db \
  psql -U "$DB_USERNAME" -c 'CREATE DATABASE zipick_notification;'

# 저장소의 zipick-notification/db/init.sql 내용을 새 DB 에 적용
docker compose -f docker-compose.prod.yml exec -T db \
  psql -U "$DB_USERNAME" -d zipick_notification < init.sql
```

> `init.sql` 은 맨 앞에서 테이블을 `DROP` 합니다. **새 DB 에 처음 적용할 때만** 그대로 쓰고,
> 이후에는 변경분만 반영하세요.

### 같은 compose 프로젝트 이름을 씁니다

`docker-compose.prod.yml` 최상단에 `name: zipick` 이 고정돼 있습니다.
어느 경로에서 실행해도 **같은 컨테이너·볼륨**(`zipick_db-data`, `zipick_rabbitmq-data`, `zipick_caddy-data` …)에 붙습니다.
**이름이 달라지면 빈 볼륨이 새로 생겨 DB 가 비어 보입니다.**

---

## 8. 사용법

### 배포하기

1. GitHub → **Actions** → **Deploy** → **Run workflow**
2. Branch 는 `dev` (다른 브랜치면 즉시 실패)
3. `image_tag` 비워 두기, `build_only` 끄기
4. Run workflow

**EC2 가 꺼져 있으면** 배포를 시작하기 전에 `PingStatus` 확인에서 멈춥니다. 서버를 먼저 켜 주세요.

### 이미지만 만들고 배포하지 않기

`build_only` 를 켜면 ①②까지만 하고 배포하지 않습니다. **AWS 설정 없이도 실행됩니다.**
빌드만 확인하거나 패키지를 처음 만들 때 씁니다. 패키지는 비공개 그대로 두면 됩니다.

### 롤백하기

1. Run workflow → `image_tag` 에 이전 태그 입력 (예: `sha-a1b2c3d`)
2. 빌드를 건너뛰고 **그 이미지와 같은 커밋의 compose** 를 함께 되돌립니다

```
sha-a1b2c3d → 커밋 a1b2c3d... → raw.githubusercontent.com/<repo>/a1b2c3d.../deploy/docker-compose.prod.yml
```

옛 이미지에 최신 compose 가 붙는 불일치가 생기지 않습니다.
이전 태그는 저장소 → Packages 또는 과거 실행의 Job Summary 에서 찾을 수 있습니다.
**이미지는 최근 5개 버전만 남으므로 그 범위 안에서만 롤백할 수 있습니다.**

**안전하게 실패하는 경우**

| 상황 | 동작 |
| --- | --- |
| 태그가 `sha-[0-9a-f]{7}` 형식이 아님 | 즉시 실패 (임의 문자열이 이미지 참조에 들어가지 않음) |
| 그 커밋을 찾을 수 없음 | 즉시 실패 |
| 그 커밋의 compose 가 이미지 변수(`BACKEND_IMAGE` 등)를 받지 않음 | 즉시 실패 — CD 도입 이전 버전으로는 롤백 불가 |
| 그 태그의 이미지가 GHCR 에 없음 | 즉시 실패 |

그 이전 버전으로 돌아가야 한다면 `deploy/deploy.sh` 수동 배포를 쓰세요.

### ⚠️ DB 스키마는 롤백되지 않습니다

백엔드는 `ddl-auto: validate` 입니다. 스키마는 사람이 수동으로 반영하며
**CD 는 DDL 을 실행하지도 되돌리지도 않습니다.**

| 앞선 배포가 한 일 | 이미지 롤백 결과 |
| --- | --- |
| 테이블·컬럼 **추가**만 | 안전 (구버전은 모르는 테이블을 무시) |
| 컬럼 **삭제·이름 변경** | ❌ 구버전이 `validate` 실패로 **기동 불가** |

**스키마를 바꾼 배포는 이미지 롤백만으로 되돌릴 수 없습니다.** 스키마를 먼저 되돌려야 합니다.

### 수동 배포로 돌아가기

```bash
./deploy/deploy.sh ubuntu@<EC2_HOST> ~/.ssh/<KEY>.pem
```

`deploy.sh` 는 CD 도입 이후에도 **변경하지 않았습니다.** compose 의 이미지 이름이
`${BACKEND_IMAGE:-zipick-backend:latest}` 형태로 바뀌었지만, 환경 변수를 주지 않으면
**기존과 똑같이** 로컬에서 빌드한 이미지를 씁니다.

---

## 9. 실패했을 때

| 메시지 | 원인과 조치 |
| --- | --- |
| `배포는 dev 브랜치에서만 실행합니다` | 브랜치를 `dev` 로 선택 |
| `GitHub Secrets 가 설정되지 않았습니다` | 6-5 의 Secrets 설정. 이미지도 만들어지지 않았습니다 |
| `Backend CI 가 이 커밋에서 성공하지 않았습니다` | 그 커밋의 CI 를 먼저 통과시키기 |
| `build_only 와 image_tag 는 함께 쓸 수 없습니다` | 롤백은 이미 있는 이미지를 쓰므로 빌드할 것이 없습니다 |
| `... 를 받을 수 없습니다` (GHCR) | 패키지의 **Manage Actions access** 에 이 저장소가 연결돼 있는지 확인. **서버에는 아직 접속하지 않은 상태** |
| `EC2 가 SSM 에 연결돼 있지 않습니다 (PingStatus=...)` | EC2 가 꺼져 있거나 SSM Agent·인스턴스 역할이 없음 |
| `서버에 AWS CLI 가 없습니다` | `sudo snap install aws-cli --classic` |
| `GHCR 로그인 실패` | 인스턴스 역할 권한·리전 확인. `AccessDenied (kms:Decrypt)` 가 보이면 인스턴스 역할에 `alias/aws/ssm` 복호화 권한 추가 |
| `운영 DB 볼륨 'zipick_db-data' 이 없습니다` | 첫 설치거나 프로젝트명이 다름. `deploy.sh` 로 수동 설치하거나 `DB_VOLUME` 지정 |
| `받은 compose 파일이 현재 .env 로 해석되지 않습니다` | `.env` 에 빠진 값이 있음. **기존 파일은 그대로 두고 중단**됨 |
| `compose 서비스 목록이 기대와 다릅니다` | compose 가 다른 구성을 가리킴 (기대: 서비스 5개) |
| `알림센터 DB(zipick_notification)나 그 테이블이 없습니다` | 7장의 수동 생성 절차 수행 |
| 헬스 체크 실패 | Actions 로그에 컨테이너 상태 + 최근 로그 80줄이 출력됨 |
| `알림센터가 healthy 가 아닙니다` | `docker compose -f docker-compose.prod.yml logs notification rabbitmq` 확인 |

**어떤 실패든 DB 와 볼륨은 그대로입니다.** `down` 계열을 쓰지 않고, `db`·`rabbitmq` 는
`--no-recreate` 로만 다루며, 위험한 단계 전에 먼저 확인하고 멈추기 때문입니다.

---

## 10. 파일 구성

| 파일 | 역할 |
| --- | --- |
| `.github/workflows/deploy.yml` | verify → build → deploy 3단계 워크플로 |
| `deploy/remote-deploy.sh` | **서버에서 도는 배포 절차 7단계.** 사람이 서버에서 직접 실행해도 동일 |
| `deploy/ssm-run.sh` | 스크립트를 SSM 으로 보내고 결과를 받아 옴. **전달 수단을 바꾸려면 이 파일만 교체** |
| `deploy/docker-compose.prod.yml` | 서비스 5개 정의. CD 와 수동 배포가 같이 씀 (이미지 이름은 변수) |
| `deploy/deploy.sh` | 기존 수동 배포 (변경 없음, 복구 수단) |
| `deploy/.env.prod.example` | 서버 `.env` 양식 |

---

## 11. 아직 확인하지 못한 것 / 남은 일

### 첫 배포 때 직접 확인해야 하는 것

- SSM 왕복 (OIDC → PutParameter → SendCommand → GetCommandInvocation → DeleteParameter)
- EC2 가 Parameter Store 에서 임시 토큰을 꺼내 비공개 이미지를 pull 하는지,
  그 뒤 SSM 명령 기록·Actions 로그에 토큰 값이 없는지
- ARM64 이미지가 t4g 에서 실제로 실행되는지
- 서버의 compose 프로젝트명이 `zipick`, 볼륨이 `zipick_db-data` 인지
  (다르면 볼륨 가드가 먼저 막아 줍니다)
- 헬스 체크 경로 `/api/v1/houses/filter-options` 가 배포 환경에서 200 을 주는지
- 알림센터 healthcheck 통과 여부

> 첫 실행은 **EC2 를 켜 둔 상태에서**, 서비스 영향이 적은 시간에 하세요.
> DB 는 보존되지만 컨테이너 교체 중 수십 초 접속이 끊길 수 있습니다.

### 남은 일

- **백엔드 컨테이너 메모리 한도.** `backend` 는 한도가 없어 `MaxRAMPercentage=40` 이
  호스트 1.8GB 기준(힙 약 720MB)으로 계산됩니다. 실측 사용량(약 430MB)을 보고 한도와 힙 비율을
  **함께** 정해야 합니다 — 한도만 낮추면 OOM 으로 서비스가 내려갈 수 있어 아직 바꾸지 않았습니다.
- 자동 배포 트리거(`push`)는 수동 배포가 충분히 검증된 뒤에 검토합니다.
