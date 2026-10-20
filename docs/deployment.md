# 배포·운영 체크리스트

이 문서는 ZIPICK의 **현재 소스 설정 기준으로 배포 담당자가 확인할 항목**을 정리합니다. 기존 중간 배포 인프라의 실제 서버 구성·도메인·컨테이너 서비스명은 이 문서에 제공되지 않았으므로, 특정 서버 명령이나 도메인을 임의로 확정하지 않습니다.

## 1. 실행 프로파일과 필수 환경

| 항목 | 확인 내용 |
| --- | --- |
| Spring 프로파일 | 배포 백엔드에서 `SPRING_PROFILES_ACTIVE=prod` 지정 여부 확인 (기본은 `local`) |
| DB | `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD` 주입. `application-prod.yml`은 운영 DB 환경 변수 사용 |
| JWT | `JWT_SECRET`: Base64 인코딩, 디코딩 시 32바이트 이상. 실제 키는 Git/이미지에 포함하지 않음 |
| 마이홈 / 카카오 | 백엔드가 `myhome.api.service-key`, `kakao.local.rest-api-key`를 실행 환경에서 읽을 수 있어야 함 |
| 알림센터 연결 | 백엔드와 알림센터에 같은 `NOTIFICATION_INTERNAL_KEY`. 백엔드는 `NOTIFICATION_BASE_URL`(compose 안에서는 `http://notification:8081`) |
| 알림 요청 큐 | RabbitMQ 컨테이너(`RABBITMQ_USER`, `RABBITMQ_PASSWORD`). 백엔드 `NOTIFICATION_QUEUE_ENABLED=true` + `RABBITMQ_HOST`, 알림센터 `RABBITMQ_URL`. 큐가 없으면 HTTP 로만 동작 |
| 이메일 알림 | 알림센터에 `NOTIFICATION_EMAIL_PROVIDER=smtp`(+ `MAIL_*`) 또는 `ses`(+ `NOTIFICATION_EMAIL_FROM`, AWS 자격 증명). 비우면 슬랙만 동작 |
| DB 스키마 | `ddl-auto: validate`; 배포 DB에 `local-init.sql` / `notice-init.sql` / `infra-init.sql` 같은 **DROP/CREATE 초기화 스크립트 실행 금지** |

현재 로컬 개발용 외부 API 키는 `config/application-secret.yml` 파일로 관리하지만, 실제 배포 방식에 맞춰 **비밀 설정이 런타임에 안전하게 전달되는지** 확인해야 합니다.

## 2. 프론트–백엔드 연결 및 쿠키/CORS

`application-prod.yml`에는 `cors.allowed-origins` 운영 값이 **TODO로 남아 있습니다**. 실제로 배포된 프론트엔드 origin을 명시적으로 등록해야 합니다.

```yaml
# application-prod.yml에 설정할 예시 (실제 주소로 교체)
cors:
  allowed-origins:
    - https://frontend.example.com
```

`SecurityConfig`는 `allowCredentials(true)`를 사용하고 있으며, 프론트엔드 공용 axios 인스턴스는 `withCredentials: true`입니다. 쿠키가 필요한 교차 출처 요청을 위해 다음을 점검합니다.

- CORS의 허용 origin은 `https://...`를 포함한 **실제 브라우저 origin(스킴·호스트·포트)**이어야 합니다. 쿠키 사용 시 `*`를 쓰지 않습니다.
- `cookie.secure=true`를 사용하는 운영 환경은 브라우저 관점에서 HTTPS가 필요합니다.
- **cross-origin과 cross-site는 서로 다릅니다.** 다른 서브도메인이라도 same-site일 수 있으므로, 프론트·백엔드 도메인이 다르다는 이유만으로 항상 `SameSite=None`이 필요한 것은 아닙니다.
- 실제로 **cross-site 쿠키 전송이 필요하면 `SameSite=None; Secure`**를 설정하고, 브라우저의 서드파티 쿠키 정책까지 검증합니다.
- 로그인 응답의 `Set-Cookie`, `POST /api/auth/reissue`, 로그아웃 쿠키 만료 동작을 배포 주소에서 직접 확인합니다.
- Access Token은 응답 바디로 받아 메모리에 유지하고, Refresh Token은 HttpOnly 쿠키로 전달합니다. 자세한 흐름은 [인증 문서](auth.md)에 있습니다.

프론트엔드 `src/api/client.ts`의 API base URL은 `import.meta.env.VITE_API_BASE_URL || ''`입니다. 배포 시 다음 중 실제 구성에 맞는 방법을 사용합니다.

- 같은 출처의 리버스 프록시가 `/api`를 백엔드로 전달한다면 기본 상대 경로(`''`) 사용
- 별도 API origin을 직접 호출한다면 프론트 빌드 시 `VITE_API_BASE_URL`을 설정하고 해당 origin을 CORS에 등록

Vite의 `VITE_*` 환경 변수는 프론트엔드 **빌드 시점** 값입니다. 이미 빌드된 정적 파일에는 컨테이너 런타임 환경 변수만 바꾸어도 자동 반영되지 않을 수 있습니다.

> **보안 점검 필요:** 현재 `SecurityConfig`는 `/api/internal/**`와 `/api/external/**`를 `permitAll()` 처리하고 있으며 운영 보호 TODO가 있습니다. 배포 전 해당 경로의 노출과 권한 제한 여부를 담당자와 확인해야 합니다. 또한 CSRF가 비활성화되어 있어 cross-site 쿠키 사용 방식이 바뀐다면 관련 위협을 별도 검토하세요.

## 3. 주변 인프라 배치 운영

주변 인프라는 웹 백엔드에서만 `@Scheduled`로 자동 수집합니다. 공고 수집 전용 `NoticeCollectionApplication`에서는 인프라 배치가 실행되지 않습니다.

| 항목 | 현재 기본값 |
| --- | --- |
| 대상 | `OPEN`·`UPCOMING` 공고의 활성 Housing 중 좌표 보유 |
| 최초 실행 | 백엔드 기동 약 10초 후 |
| 회차당 최대 대상 | Housing 100개 (`zipick.infra.collection.batch-size`) |
| 회차 간 대기 | 이전 회차 종료 후 **5분** (`interval-ms: 300000`) |
| 수집 내용 | 주택당 카카오 카테고리 검색 최대 6회, 순차 실행 |
| 캐시 TTL | 30일. 미수집·만료·정책 변경·좌표 변경 시 수집 대상 |
| 연속 실패 시 | API 호출 3회 연속 실패하면 해당 회차 중단, 다음 회차에 다시 시도 |

### 최초 대량 수집: 일시적으로 1분 간격

로컬 (macOS / Linux / WSL, `zipick-backend/`에서 실행):

```bash
ZIPICK_INFRA_COLLECTION_INTERVAL_MS=60000 ./gradlew bootRun
```

이미 배포한 백엔드도 같은 환경 변수를 **백엔드 프로세스 또는 컨테이너에 전달하여 재시작**하면 1분 간격으로 동작합니다. Docker Compose를 사용 중이라면 백엔드 서비스에 아래 설정을 임시로 전달하는 형태입니다.

```yaml
# 실제 Compose 서비스명·배포 파일 구조에 맞게 적용
services:
  <backend-service-name>:
    environment:
      ZIPICK_INFRA_COLLECTION_INTERVAL_MS: "60000"
```

**위 YAML은 설명용 예시**이며 현재 저장소에서 배포용 Compose 파일이나 실제 백엔드 서비스명을 확인한 것은 아닙니다. 배포 담당자는 현재 사용 중인 환경 변수 전달 방식(.env, Compose, 배포 파이프라인 등)에 맞춰 적용해야 합니다.

`fixedDelay`는 **100개 수집 후 1분 대기**를 뜻하며 1분마다 100개가 동시에 실행된다는 뜻이 아닙니다. 1분은 카카오가 보장하는 최소 안전 간격이 아니므로 호출 실패·429·쿼터를 확인합니다. 로컬과 배포 환경에서 동일 Kakao REST 키를 공유하면 사용량도 같은 앱 쿼터에 반영될 수 있으므로 키·앱 사용량을 확인해야 합니다.

### 수집 완료 후 기본 5분으로 복귀

1. 백엔드 로그에서 `주변 인프라 수집 완료: 대상=..., 저장=..., 호출실패=...`을 확인합니다. 회차가 끝났다는 로그만으로 전체 대상이 완료된 것은 아닙니다.
2. 아래 조회 결과에서 수집 대상 수와 스냅샷 보유 수를 확인합니다. 스냅샷이 있어도 정책·좌표 불일치 또는 TTL 만료일 수 있으므로 최종 확인은 API 응답의 `FRESH` 및 서버 로그와 함께 합니다.
3. 임시 `ZIPICK_INFRA_COLLECTION_INTERVAL_MS` 값을 **제거**하고 백엔드를 **재시작 또는 재배포**합니다. 이를 하지 않으면 1분 간격이 계속 유지됩니다. 환경 변수 미지정 시 기본은 `300000ms`(5분)입니다.
4. 재시작 후 수집 데이터는 PostgreSQL에 유지됩니다. 정상 스냅샷을 다시 수집하지 않습니다.

```sql
-- 수집 대상별 스냅샷 저장 현황 (저장 여부만 확인, FRESH 여부는 별도 확인)
SELECT n.source, n.status,
       COUNT(DISTINCT h.housing_id) AS target_housings,
       COUNT(DISTINCT s.housing_id) AS snapshot_housings
  FROM notice n
  JOIN housing h ON h.notice_id = n.notice_id
  LEFT JOIN housing_infra_snapshot s ON s.housing_id = h.housing_id
 WHERE n.status IN ('OPEN', 'UPCOMING')
   AND h.active = TRUE
   AND h.latitude IS NOT NULL AND h.longitude IS NOT NULL
 GROUP BY n.source, n.status
 ORDER BY n.source, n.status;
```

> 서버 인스턴스가 여러 개라면 주의하세요. 현재 `ReentrantLock`은 **단일 JVM 내부** 중복 실행만 막습니다. 여러 백엔드 인스턴스가 같은 DB에서 배치를 실행하면 중복 수집 위험이 있으므로, 배포 담당자는 스케줄러 실행 인스턴스를 한 개로 제한하거나 별도의 분산 락을 검토해야 합니다.

상세 수집 정책·API·캐시 상태: [주변 인프라 기능](housing-infra.md).

## 4. 배포 후 최소 점검

- 실제 배포 URL에서 프론트엔드 페이지가 로딩되는지 확인
- 로그인 → 새로고침 → 토큰 재발급 → 로그아웃 동작 확인 (쿠키·CORS 오류 점검)
- 지도 공급주택 조회 및 인프라 `FRESH` / `PENDING` 상태 확인
- 추천 API에서 인프라 점수·근거가 정상 반영되는지 확인
- 백엔드 로그에 API 타임아웃·429·DB 연결 오류 및 배치 실패가 없는지 확인
- 내부·외부 연동 API에 대한 접근 제어와 운영 DB 초기화 금지 확인

## 5. 자동 배포 (CD)

GitHub Actions 로 `dev` 커밋을 ARM64 이미지로 만들어 EC2 에 배포하는 파이프라인이 있습니다.
**수동 실행만 허용**하며, 기존 `deploy/deploy.sh` 수동 배포도 그대로 동작합니다.

설정·사용법·롤백 절차: **[CD 파이프라인](cd-pipeline.md)**

**1차 배포 범위는 `db` · `backend` · `web` 입니다.** 전용 compose 파일
(`deploy/docker-compose.phase1.yml`)을 쓰며, `rabbitmq` 와 `notification` 은 정의 자체가 없어
실수로도 실행되지 않습니다. 1차에서는 서버 `.env` 에 추가할 항목이 없습니다.

> `rabbitmq` · `notification` 은 담당자 구현이 끝난 뒤 2차에서 다룹니다. 그때
> `zipick_notification` 데이터베이스를 **수동으로** 만들어야 합니다 — 기존 DB 볼륨에서는
> compose 의 초기화 SQL 이 실행되지 않습니다. CD 는 운영 DB 에 DDL 을 실행하지 않습니다.
