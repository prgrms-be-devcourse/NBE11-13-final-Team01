# CD 파이프라인 (GitHub Actions → EC2)

`dev` 브랜치의 검증된 커밋으로 ARM64 이미지를 만들어 GHCR 에 올리고, AWS SSM 으로 EC2 에 배포합니다.
**수동 실행(`workflow_dispatch`)만 허용하며, merge 만으로는 배포되지 않습니다.**

기존 수동 배포(`deploy/deploy.sh`)는 그대로 둡니다. CD 에 문제가 생겼을 때의 복구 수단입니다.

---

## 배포 범위 — 1차와 2차를 구분합니다

| | **1차 (현재)** | **2차 (예정)** |
| --- | --- | --- |
| compose | `deploy/docker-compose.phase1.yml` | `deploy/docker-compose.prod.yml` |
| 서비스 | `db` · `backend` · `web` | + `rabbitmq` · `notification` |
| 빌드하는 이미지 | `zipick-backend`, `zipick-web` | + `zipick-notification` |
| 서버 `.env` 추가 항목 | **없음** | `RABBITMQ_USER/PASSWORD`, `NOTIFICATION_INTERNAL_KEY` |
| DB 작업 | 없음 | `zipick_notification` 생성 + DDL (수동) |
| 메모리 | 현행 유지 | 백엔드 메모리 한도 검토 필요 |

**RabbitMQ 와 알림센터는 담당자가 구현 중이며 EC2 배포가 검증되지 않았습니다.**
1차 CD 의 목표는 **이미 정상 동작 중인 구성(db · backend · web)을 안전하게 자동화**하는 것입니다.

### 왜 compose 파일을 따로 두는가

`docker-compose.prod.yml` 하나로 1차를 처리할 수 없다는 것을 확인했습니다.

- **`profiles` 로 신규 서비스를 감쌀 수 없습니다.** `backend` 가 `depends_on: rabbitmq` 를 갖고 있어,
  프로필을 끄면 `service "backend" depends on undefined service "rabbitmq": invalid compose project` 로
  프로젝트 자체가 무효가 됩니다.
- **override 파일로 덜어낼 수 없습니다.** compose 의 `depends_on` 은 병합만 되고 제거가 안 됩니다.
- **서비스를 지정해도 소용없습니다.** `docker compose ... config backend` 처럼 서비스를 지정해도
  compose 는 **파일 전체를 먼저 해석**합니다. `${RABBITMQ_USER:?}` 같은 필수 변수가 비어 있으면
  기존 `.env` 로는 `config` · `up` · `ps` 가 모두 실패합니다.

그래서 1차 전용 파일을 따로 두었습니다. **신규 서비스가 정의 자체에 없으므로 실수로도 실행되지 않습니다.**
`docker-compose.prod.yml` 은 **수정하지 않았습니다**(이미지 이름 변수화 제외). 2차에서 그대로 씁니다.

### 백엔드는 RabbitMQ 없이 정상 기동합니다

앱 코드를 바꿀 필요가 없습니다. 알림·큐 관련 빈이 모두 조건부이기 때문입니다.

```java
// NotificationQueueConfig.java · NotificationPublisher.java
@ConditionalOnProperty(prefix = "zipick.notification", name = "queue-enabled", havingValue = "true")

// NotificationScheduler.run()
if (!client.configured()) return;     // configured() = internalKey 가 비어 있지 않을 때만 true
```

1차 compose 는 이에 맞춰 두 값을 지정합니다.

| 환경 변수 | 1차 값 | 효과 |
| --- | --- | --- |
| `NOTIFICATION_QUEUE_ENABLED` | `"false"` | 큐 선언·발행 빈이 생성되지 않음 → RabbitMQ 연결 시도 없음 |
| `NOTIFICATION_INTERNAL_KEY` | `${NOTIFICATION_INTERNAL_KEY:-}` (비어도 됨) | 알림 스케줄러가 즉시 return → 알림센터 호출·경고 로그 없음 |

---

## 전체 흐름

```
[GitHub Actions]  Actions 탭 → Deploy → Run workflow (브랜치: dev)

  1. verify    dev 브랜치인가?
               AWS Secrets 가 설정돼 있는가?          ← 빌드 전에 막는다
               (새 배포) 이 커밋에서 Backend/Frontend CI 가 success 인가?
               (롤백)    태그 형식 · 해당 커밋 · 해당 이미지가 실제로 있는가?

  2. build     ubuntu-24.04-arm 러너에서 linux/arm64 네이티브 빌드
               ghcr.io/<저장소>/zipick-{backend,web}:sha-<커밋7자리> 푸시
               (알림센터 이미지는 1차에서 만들지 않는다)

  3. deploy    OIDC 로 AWS 임시 자격 증명 발급 (장기 키 없음)
               SSM Run Command 로 remote-deploy.sh 를 EC2 에 전달

[EC2]  remote-deploy.sh

  1. 운영 DB 볼륨(zipick_db-data) 존재 확인   없으면 아무것도 하지 않고 중단
  2. compose 갱신        배포 커밋 SHA 의 raw URL → config 검증 통과 후에만 교체
  3. 배포 범위 확인      rabbitmq · notification 이 들어 있으면 중단
  4. 이미지 pull         실패하면 기존 컨테이너를 건드리기 전에 중단
  5. db 는 --no-recreate (떠 있으면 그대로) → backend · web 만 --no-deps --force-recreate
  6. 헬스 체크           실패하면 컨테이너 상태 + 로그 80줄 출력 후 종료 코드 1
```

## 왜 이 구성인가

| 결정 | 이유 |
| --- | --- |
| **GHCR** (ECR 아님) | 저장소가 Public 이라 무료이고, Actions 는 `GITHUB_TOKEN` 으로 바로 푸시한다. 패키지를 Public 으로 두면 EC2 가 **인증 없이 pull** 하므로 서버에 토큰을 두지 않아도 된다 |
| **`ubuntu-24.04-arm` 러너** | EC2 가 t4g(ARM64)다. Public 저장소는 ARM64 러너를 무료로 쓸 수 있어 QEMU 에뮬레이션 없이 네이티브로 빌드한다 |
| **SSM Run Command** (SSH 아님) | GitHub Actions 러너는 IP 가 고정되지 않는다. SSH 를 쓰려면 넓은 대역을 열어야 해 "배포 담당자 IP 만 허용" 정책이 깨진다. SSM 은 **인바운드 포트를 하나도 열지 않는다** |
| **OIDC** (Access Key 아님) | GitHub Secrets 에 장기 AWS 키를 두지 않는다. 실행할 때마다 임시 자격 증명을 받는다 |
| **커밋 SHA 태그** | `latest` 는 어떤 코드인지 추적할 수 없다. `sha-a1b2c3d` 는 이미지와 커밋이 1:1 로 대응되고 그대로 롤백 입력값이 된다 |
| **EC2 에서 빌드하지 않음** | t4g.small 은 RAM 2GB 다. Gradle·Vite 빌드를 돌리면 운영 중인 서비스까지 영향을 받는다 |

---

## 운영 데이터 보존

### 같은 compose 프로젝트·같은 볼륨을 씁니다

`docker-compose.phase1.yml` 은 최상단에 프로젝트 이름을 고정합니다.

```yaml
name: zipick
```

서버의 기존 배포는 `~/zipick` 에서 실행되어 디렉터리명 기반으로 프로젝트명이 `zipick` 이 됩니다.
두 파일이 **같은 프로젝트·같은 볼륨**을 가리키는 것을 확인했습니다.

```
docker-compose.prod.yml     project=zipick → zipick_db-data, zipick_caddy-data, zipick_caddy-config, zipick_rabbitmq-data
docker-compose.phase1.yml   project=zipick → zipick_db-data, zipick_caddy-data, zipick_caddy-config
```

`name:` 을 고정했기 때문에 어느 경로에서 실행해도 같은 볼륨에 붙습니다.
**이름이 달라지면 빈 볼륨이 새로 생겨 DB 가 비어 보입니다.** 그래서 명시적으로 박아 두었습니다.

### 볼륨이 없으면 새로 만들지 않고 중단합니다

`remote-deploy.sh` 는 가장 먼저 운영 DB 볼륨을 확인합니다.

```bash
docker volume inspect "$DB_VOLUME" || fail "운영 DB 볼륨이 없습니다. CD 는 새 DB 를 만들지 않습니다."
```

볼륨이 없다는 것은 **첫 설치이거나 프로젝트명이 어긋났다**는 뜻입니다. 그대로 `up` 을 돌리면
compose 가 빈 볼륨을 만들어 DB 가 새로 초기화되므로, 존재하는 볼륨 목록을 보여 주고 멈춥니다.
첫 설치는 CD 가 아니라 `deploy/deploy.sh` 로 진행합니다.

### 그 밖의 보호 장치

- `docker compose down` 계열을 **쓰지 않습니다.**
- `db` 는 `up -d --no-recreate` 로만 다룹니다. 떠 있으면 건드리지 않습니다.
- 앱 교체는 `--no-deps` 라 `db` 에 영향을 주지 않습니다.
- 1차 compose 의 `db` 에는 **초기화 SQL 을 마운트하지 않습니다**
  (`docker-compose.prod.yml` 의 `./db:/docker-entrypoint-initdb.d:ro` 가 없습니다).
  CD 가 운영 DB 스키마를 건드릴 경로 자체를 없앴습니다.
- 서버 `.env` 는 **쓰지 않습니다.** 읽는 값은 헬스 체크 주소를 만들기 위한 `HTTP_PORT` 하나뿐입니다.

---

## 롤백

### 방법

1. Actions → Deploy → **Run workflow**
2. `image_tag` 에 되돌릴 태그 입력 — 예: `sha-a1b2c3d`
3. 빌드를 건너뛰고 **해당 이미지와 같은 커밋의 compose** 를 함께 되돌립니다

### 이미지와 설정이 같은 버전으로 맞춰집니다

태그의 7자리를 전체 커밋 SHA 로 되돌린 뒤, 그 커밋에서 compose 를 받습니다.

```
sha-a1b2c3d  →  커밋 a1b2c3d...  →  raw.githubusercontent.com/<repo>/a1b2c3d.../deploy/docker-compose.phase1.yml
```

옛 이미지에 최신 compose 가 붙는 불일치가 생기지 않습니다.

### 안전하게 실패하는 경우

| 상황 | 동작 |
| --- | --- |
| 태그 형식이 `sha-[0-9a-f]{7}` 가 아님 | 즉시 실패 (임의 문자열이 이미지 참조에 들어가지 않음) |
| 해당 커밋을 찾을 수 없음 | 즉시 실패 |
| 그 커밋에 `deploy/docker-compose.phase1.yml` 이 없음 | 즉시 실패 — **1차 CD 도입 이전 버전으로는 롤백할 수 없습니다** |
| 그 태그의 이미지가 GHCR 에 없음 | 즉시 실패 |

**롤백은 실제로 이 CD 로 배포한 적이 있는 버전만 대상으로 합니다.**
그 이전 버전으로 돌아가야 한다면 `deploy/deploy.sh` 수동 배포를 쓰세요.

### ⚠️ DB 스키마는 롤백되지 않습니다

백엔드는 `ddl-auto: validate` 입니다. 스키마는 담당자가 수동으로 반영하며, **CD 는 DDL 을 실행하지도
되돌리지도 않습니다.**

| 앞선 배포가 한 일 | 이미지 롤백 결과 |
| --- | --- |
| 테이블·컬럼 **추가**만 | 안전. 구버전은 모르는 테이블을 무시한다 |
| 컬럼 **삭제·이름 변경** | ❌ 구버전이 `validate` 에 실패해 **기동하지 못한다** |

**스키마를 변경한 배포는 이미지 롤백만으로 되돌릴 수 없습니다.** 스키마를 먼저 수동으로 되돌려야 합니다.

---

## AWS 담당자 설정 체크리스트

CD 를 실행하려면 아래가 먼저 끝나야 합니다. **아직 완료되지 않았으며, AWS 리소스를 만들거나 바꾸지 않았습니다.**
설정 전에 워크플로를 실행하면 `verify` 단계에서 멈추므로 이미지도 만들어지지 않습니다.

### 1. GitHub OIDC Provider 등록 (AWS 계정당 한 번)

| 항목 | 값 |
| --- | --- |
| Provider URL | `https://token.actions.githubusercontent.com` |
| Audience | `sts.amazonaws.com` |

### 2. Actions 가 맡을 IAM 역할 생성

신뢰 정책 — **저장소와 브랜치를 반드시 제한**합니다. 제한하지 않으면 다른 저장소도 이 역할을 쓸 수 있습니다.

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

권한 정책 — 대상 인스턴스 하나로 좁힙니다.

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
    }
  ]
}
```

> `ssm:DescribeInstanceInformation` 은 리소스 단위 제한을 지원하지 않아 `*` 가 필요합니다.
> 읽기 전용이며, 배포 전에 "EC2 가 켜져 있고 SSM 에 연결됐는지" 확인하는 데만 씁니다.

### 3. EC2 쪽 준비

- 인스턴스에 **IAM 인스턴스 프로파일** 연결 — 관리형 정책 `AmazonSSMManagedInstanceCore`
- **SSM Agent 실행 확인** — Ubuntu 공식 AMI 에는 snap 으로 설치돼 있습니다.
  ```bash
  sudo snap services amazon-ssm-agent
  ```
- SSM 엔드포인트로 **아웃바운드 HTTPS(443)** 허용
- **인바운드 규칙은 바꾸지 않습니다.** SSH 22번은 지금처럼 담당자 IP 만 유지하세요.

### 4. GHCR 패키지를 Public 으로 전환 — 최초 1회

**EC2 는 `docker login` 없이 `docker pull` 합니다.** 패키지가 Private 이면 서버에서 받지 못해 배포가 실패합니다.

패키지는 **처음 푸시할 때 만들어지므로, 그 전에는 공개 설정을 할 수 없습니다.**
그래서 워크플로에 `build_only` 옵션을 뒀습니다. 아래 "최초 1회 절차"를 따르세요.

1차에서 공개할 패키지는 `zipick-backend`, `zipick-web` 두 개입니다 (알림센터는 2차).

---

## GitHub 설정

### Secrets (Settings → Secrets and variables → Actions → Secrets)

**저장소가 Public 이라 Actions 로그도 공개됩니다.** AWS 계정 ID·인스턴스 ID 가 로그에 남지 않도록
Variables 가 아니라 Secrets 에 넣습니다.

| 이름 | 용도 |
| --- | --- |
| `AWS_ROLE_ARN` | 위 2번에서 만든 IAM 역할 ARN. 계정 ID 가 들어 있음 |
| `EC2_INSTANCE_ID` | 배포 대상 인스턴스 ID (`i-0abc...`) |
| `VITE_KAKAO_MAP_KEY` | 카카오 JavaScript 키. 프론트 **이미지 빌드 시점**에 필요 |

> `VITE_KAKAO_MAP_KEY` 는 원래 브라우저에 노출되는 공개 키이고 이미지 안에도 그대로 들어갑니다.
> 저장소에 평문으로 두지 않기 위해 Secrets 를 쓰는 것이며, 실제 보호는 **카카오 콘솔의 도메인 등록**으로 합니다.

### Variables (같은 화면의 Variables 탭) — 선택

| 이름 | 기본값 | 용도 |
| --- | --- | --- |
| `AWS_REGION` | `ap-northeast-2` | 다른 리전이면 지정 |
| `APP_DIR` | `/home/ubuntu/zipick` | 서버 배포 디렉터리가 다르면 지정 |

### 서버 `.env` 는 그대로 둡니다

운영 비밀값(`DB_PASSWORD`, `JWT_SECRET`, `MYHOME_API_KEY`, `KAKAO_REST_API_KEY` 등)은
**EC2 의 `/home/ubuntu/zipick/.env` 에만** 둡니다. 파이프라인이 단순해지고 비밀값이 CI 로그를 거치지 않습니다.

**1차 배포에는 `.env` 에 추가할 항목이 없습니다.** 기존 값 그대로 동작하는 것을 확인했습니다
(알림·큐 변수가 하나도 없는 `.env` 로 `docker-compose.phase1.yml` 이 정상 해석됩니다).

알림센터·RabbitMQ 용 항목(`RABBITMQ_USER`, `RABBITMQ_PASSWORD`, `NOTIFICATION_INTERNAL_KEY`)은
**2차에서 필요**합니다. 지금 미리 채워 둘 필요가 없습니다.

---

## 사용법

### 최초 1회 절차 — 패키지를 Public 으로 만들기

GHCR 패키지는 **처음 푸시할 때 생성**되므로, 그 전에는 공개로 바꿀 수 없습니다.
첫 실행만 빌드까지 하고 멈춘 뒤 공개 설정을 하고, 그다음에 실제 배포를 합니다.

1. Actions → **Deploy** → Run workflow (Branch: `dev`)
2. **`build_only` 를 켭니다.** `image_tag` 는 비워 둡니다.
   → 이미지 2개를 GHCR 에 올리고 **배포는 하지 않습니다.**
   AWS 설정이 아직이어도 실행됩니다 (서버에 접속하지 않으므로).
3. 저장소 → **Packages** → `zipick-backend`, `zipick-web` 을 각각 열고
   **Package settings → Change visibility → Public**
4. 다시 Run workflow — 이번에는 **`build_only` 를 끕니다.**
   같은 커밋이면 빌드 캐시로 빨리 끝나고, 배포까지 진행합니다.

2단계가 끝나면 실행 화면 요약(Job Summary)에 패키지 이름과 다음 단계가 표시됩니다.

> **왜 필요한가:** 이 단계를 건너뛰고 바로 배포하면 서버의 `docker pull` 에서 실패합니다.
> 기존 컨테이너를 교체하기 전에 멈추므로 서비스는 무사하지만, 한 번 헛돌게 됩니다.
> 그래서 배포 job 은 서버에 들어가기 전에 **인증 없는 pull 이 되는지 먼저 확인**하고,
> 안 되면 무엇을 해야 하는지 알려 주며 멈춥니다.

### 배포하기

1. GitHub → **Actions** → **Deploy** → **Run workflow**
2. Branch 는 `dev` 선택 (다른 브랜치면 즉시 실패합니다)
3. `image_tag` 는 **비워 둡니다** (현재 `dev` 커밋으로 새로 빌드)
4. `build_only` 는 **끕니다**
5. Run workflow

### 배포가 실패하면

| 증상 | 원인과 조치 |
| --- | --- |
| `GitHub Secrets 가 설정되지 않았습니다` | AWS 설정이 아직입니다. 이미지도 만들어지지 않았습니다 |
| `... 를 인증 없이 받을 수 없습니다` | GHCR 패키지가 Private 입니다. 위 "최초 1회 절차" 3번을 하고 다시 실행하세요. **서버에는 접속하지 않은 상태**이고 이미지는 이미 올라가 있어 다시 빌드되지 않습니다 |
| `build_only 와 image_tag 는 함께 쓸 수 없습니다` | 롤백은 이미 있는 이미지를 쓰므로 빌드할 것이 없습니다 |
| `Backend CI 가 이 커밋에서 성공하지 않았습니다` | 해당 커밋의 CI 를 먼저 통과시킵니다 |
| `EC2 가 SSM 에 연결돼 있지 않습니다 (PingStatus=...)` | EC2 가 꺼져 있거나 SSM Agent·IAM 역할이 없습니다 |
| `운영 DB 볼륨 'zipick_db-data' 이 없습니다` | 첫 설치이거나 프로젝트명이 다릅니다. `deploy.sh` 로 수동 설치하거나 `DB_VOLUME` 을 알려 주세요 |
| `받은 compose 파일이 현재 .env 로 해석되지 않습니다` | 서버 `.env` 에 필요한 값이 빠졌습니다. **기존 파일은 그대로 두고 중단**합니다 |
| `1차 배포 대상이 아닌 서비스가 들어 있습니다` | compose 가 2차 구성을 가리키고 있습니다 |
| 헬스 체크 실패 | Actions 로그에 컨테이너 상태와 최근 로그 80줄이 출력됩니다 |

**실패해도 DB 와 볼륨은 그대로입니다.** `down` 계열을 쓰지 않고, `db` 는 `--no-recreate` 로만 다루며,
위험한 단계 전에 먼저 검증하고 멈추기 때문입니다.

### 수동 배포로 되돌아가기

CD 에 문제가 있으면 기존 방식이 그대로 동작합니다.

```bash
./deploy/deploy.sh ubuntu@<EC2_HOST> ~/.ssh/<KEY>.pem
```

`deploy.sh` 는 `docker-compose.prod.yml` 을 쓰며 **변경하지 않았습니다**.
이미지 이름이 `${BACKEND_IMAGE:-zipick-backend:latest}` 형태로 바뀌었지만, 환경 변수를 주지 않으면
**기존과 똑같이 로컬 이미지**를 씁니다.

> 다만 `docker-compose.prod.yml` 에는 `rabbitmq`·`notification` 이 들어 있어,
> 수동 배포를 하려면 서버 `.env` 에 `RABBITMQ_USER`·`RABBITMQ_PASSWORD`·`NOTIFICATION_INTERNAL_KEY` 가
> 있어야 합니다. 없으면 compose 가 해석되지 않습니다 (CD 와 무관한 기존 제약입니다).

---

## 파일 구성

| 파일 | 역할 |
| --- | --- |
| `.github/workflows/deploy.yml` | 검증 → 빌드 → 배포 3단계 워크플로 |
| `deploy/docker-compose.phase1.yml` | **1차 CD 전용.** db · backend · web 만 |
| `deploy/ssm-run.sh` | 스크립트를 SSM 으로 EC2 에 보내고 결과를 받아 온다. **전달 수단을 바꾸려면 이 파일만 교체** |
| `deploy/remote-deploy.sh` | 서버에서 실제로 도는 배포 절차. 사람이 서버에서 직접 실행해도 동일하게 동작 |
| `deploy/deploy.sh` | 기존 수동 배포 (변경 없음, 복구 수단으로 유지) |
| `deploy/docker-compose.prod.yml` | 2차용 전체 구성. 이미지 이름만 변수화 |

---

## 아직 EC2 에서만 확인할 수 있는 것

로컬에서는 검증할 수 없어 **첫 배포 때 직접 확인**해야 하는 항목입니다.

- SSM Run Command 왕복 (OIDC → SendCommand → GetCommandInvocation)
- EC2 에서 GHCR 공개 이미지 pull 이 인증 없이 되는지
- ARM64 이미지가 t4g 에서 실제로 실행되는지
- 서버의 실제 compose 프로젝트명이 `zipick` 이고 볼륨이 `zipick_db-data` 인지
  (다르면 `DB_VOLUME` 가드가 먼저 막아 줍니다)
- 헬스 체크 경로 `/api/v1/houses/filter-options` 가 배포 환경에서 200 을 주는지

> 첫 실행은 **EC2 를 켜 둔 상태에서**, 서비스 영향이 적은 시간에 하세요.
> DB 는 보존되지만 컨테이너 교체 중 수십 초 정도 접속이 끊길 수 있습니다.

## 2차에서 할 일

- 알림센터 기능 구현 완료 및 EC2 배포 검증
- `zipick_notification` 데이터베이스 수동 생성 + `zipick-notification/db/init.sql` 적용
  (**CD 는 운영 DB 에 DDL 을 실행하지 않습니다**)
- 서버 `.env` 에 `RABBITMQ_USER`, `RABBITMQ_PASSWORD`, `NOTIFICATION_INTERNAL_KEY` 추가
- 백엔드 컨테이너 메모리 한도 검토 — t4g.small(2GB)에서 컨테이너가 3개 → 5개로 늘어납니다.
  `backend` 는 한도가 없어 `MaxRAMPercentage=40` 이 호스트 2GB 기준(힙 약 800MB)으로 계산됩니다.
- 워크플로의 `COMPOSE_PATH` 를 `deploy/docker-compose.prod.yml` 로 바꾸고,
  알림센터 이미지 빌드 단계와 `remote-deploy.sh` 의 서비스 목록을 함께 확장
