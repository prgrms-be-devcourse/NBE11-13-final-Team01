# zipick-notification (알림센터, Go)

**순수 전송 서버.** 다른 서버가 "이 템플릿을, 이 변수로, 이 주소에, 이 채널로, 이때" 보내라고 요청하면 그대로 보낸다.
누구에게·보내도 되는지·언제는 요청하는 쪽이 정한다 (알림센터는 사용자·설정·동의를 모른다).
템플릿(`templates/*.yaml`) · 아웃박스(채널별 재시도, 중복 방지) · 묶음 · 채널(슬랙, 이메일 SMTP/SES)을 가진다.
API 스펙은 `docs/notification-api.md`, 설계는 `docs/notification-center.md`, 언어 선택 근거(측정)는 설계 문서 참고.

## 폴더 (Java 팀원을 위한 안내)

Go 는 Spring 처럼 계층(controller/service/repository)으로 나누기보다 **기능(도메인)별 패키지**로 나눈다.
한 패키지 안에 그 기능의 모델·DB 조회·HTTP 핸들러가 함께 있다.

| 경로 | 역할 | Spring 으로 치면 |
| --- | --- | --- |
| `cmd/server/main.go` | 진입점. 설정 → DB → 라우터 → 스케줄러 순으로 조립 | `@SpringBootApplication` + 설정 클래스 |
| `internal/config` | 환경 변수 읽기 | `@ConfigurationProperties` |
| `internal/httpx` | 응답 형식 `{success,data,error}`, 오류 코드, 내부 키 검사 | `ApiResponse`, `GlobalExceptionHandler`, 필터 |
| `internal/db` | PostgreSQL 연결 풀, 시각 타입 | `DataSource`, JPA 변환기 |
| `internal/template` | yaml 템플릿 읽기·검증, `{{변수}}` 바인딩(채널별), 읽기 전용 핸들러 | `template` 패키지 |
| `internal/channel` | 보내는 채널 인터페이스 + 슬랙/SMTP/SES 구현 | `NotificationChannel` 구현체들 |
| `internal/message` | 요청 검사 → 바인딩 → 아웃박스 → 채널별 발송·재시도, `delivery: now`, tag 조회, 묶음, 90일 주소 가림 | `MessageService` |
| `internal/scheduler` | 1분 주기: 대기 메시지 보내기, 하루 1회 주소 가림 | `@Scheduled` |
| `internal/queue` | RabbitMQ 리스너: 다른 서버가 던진 알림 요청을 꺼내 아웃박스에 적음 | `@RabbitListener` |
| `templates/*.yaml` | 문구 (코드·담당자·변수·채널별). 바꾸면 PR | |
| `db/init.sql` | 테이블 DDL (messages, deliveries, batches) | |

`internal/` 아래 패키지는 Go 규칙상 이 모듈 밖에서 import 할 수 없다 (캡슐화).

## 실행 (로컬)

```bash
# DB 한 번
docker exec zipick-dev-postgres psql -U zipick -d postgres -c "CREATE DATABASE zipick_notification OWNER zipick;"
docker exec -i zipick-dev-postgres psql -U zipick -d zipick_notification -f - < db/init.sql

# Go 가 설치돼 있으면
DB_USERNAME=zipick DB_PASSWORD=... NOTIFICATION_INTERNAL_KEY=... go run ./cmd/server
# 없으면 Docker 로 (Dockerfile 은 배포 담당 로컬에만 있음)
```

환경 변수 목록은 `internal/config/config.go` 에 있다.

## 테스트

```bash
go test ./...        # DB 없이 도는 단위 테스트 (DB 통합 테스트는 자동으로 건너뜀)
```

SQL 까지 확인하는 통합 테스트(중복 방지, 채널별 재시도, delivery=now, 예약, 묶음 취소, 주소 가림)는 테스트 DB 가 있을 때만 돈다.
이름이 `_test` 로 끝나는 빈 DB 에 `db/init.sql` 을 적용하고 주소를 넣는다 (테스트마다 테이블을 비운다).

```bash
docker exec zipick-dev-postgres psql -U zipick -d postgres -c "CREATE DATABASE zipick_notification_test;"
docker exec -i zipick-dev-postgres psql -U zipick -d zipick_notification_test < db/init.sql
NOTIFICATION_TEST_DB_URL=postgres://zipick:<비밀번호>@localhost:5432/zipick_notification_test go test -p 1 ./...
```
