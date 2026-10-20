# zipick-notification (알림센터, Go)

다른 서버는 "무엇을 누구에게"만 요청하고(템플릿 코드 + 변수), 어떻게 보낼지는 여기서 정한다.
템플릿 · 수신 동의 · 아웃박스(재시도, 중복 방지) · 예약 발송 · 채널(슬랙, 이메일 SMTP/SES)을 가진다.
설계와 API 는 `docs/notification-center.md`, 언어 선택 근거(측정)는 설계 문서 참고.

## 폴더 (Java 팀원을 위한 안내)

Go 는 Spring 처럼 계층(controller/service/repository)으로 나누기보다 **기능(도메인)별 패키지**로 나눈다.
한 패키지 안에 그 기능의 모델·DB 조회·HTTP 핸들러가 함께 있다.

| 경로 | 역할 | Spring 으로 치면 |
| --- | --- | --- |
| `cmd/server/main.go` | 진입점. 설정 → DB → 라우터 → 스케줄러 순으로 조립 | `@SpringBootApplication` + 설정 클래스 |
| `internal/config` | 환경 변수 읽기 | `@ConfigurationProperties` |
| `internal/httpx` | 응답 형식 `{success,data,error}`, 오류 코드, 내부 키 검사 | `ApiResponse`, `GlobalExceptionHandler`, 필터 |
| `internal/db` | PostgreSQL 연결 풀, 시각 타입 | `DataSource`, JPA 변환기 |
| `internal/template` | 템플릿 모델·조회·`{{변수}}` 치환·기본 템플릿·핸들러 | `template` 패키지 전체 |
| `internal/subscription` | 사용자별 수신 주소·동의 | `subscription` |
| `internal/channel` | 보내는 채널 인터페이스 + 슬랙/SMTP/SES 구현 | `NotificationChannel` 구현체들 |
| `internal/message` | 아웃박스: 요청 접수(enqueue), 발송(dispatch), 테스트 발송, 내역 | `MessageService` |
| `internal/campaign` | 예약 발송 | `CampaignService` |
| `internal/scheduler` | 1분 주기: 캠페인 풀기 → 대기 메시지 보내기 | `@Scheduled` |
| `internal/queue` | RabbitMQ 리스너: 메인 서버가 던진 알림 요청을 꺼내 아웃박스에 적음 | `@RabbitListener` |
| `db/init.sql` | 테이블 DDL (Spring 버전과 같은 스키마) | |

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
go test ./...
```
