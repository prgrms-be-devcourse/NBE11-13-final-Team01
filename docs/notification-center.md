# 알림센터 (zipick-notification)

담당: 안서호 · 2026-10-20 · 강사 피드백("알림은 마케팅과 엮이니 알림센터로 분리하고, 템플릿은 알림센터가 가진다") 반영

**구현 언어: Go** (표준 라이브러리 `net/http` + pgx). 처음엔 Spring 으로 만들었다가, 5개 후보를 같은 조건에서 측정한 뒤 Go 로 다시 만들었다.
측정과 결정 근거는 설계 문서의 "알림센터 언어 선택" 섹션에 있다. 요약: 발송 속도는 언어 차이가 없었고(외부 API 응답 시간이 정함), 메모리가 Go 9MB vs Spring 164~204MB 로 EC2 한 대(1.8GB)를 메인 서버·Kafka 와 나눠 쓰는 조건에서 Go 가 유리했다.
API 계약(경로·JSON·응답 형식)과 DB 스키마는 Spring 버전과 같아서 메인 서버 코드는 바뀌지 않았다. 실측: 이미지 33MB, 대기 메모리 약 5MB, 기동 0.5초 미만.

## 왜 분리했나

| 기준 | 뜻 | 설계에 반영된 것 |
| --- | --- | --- |
| 분리 | 인증·추천·관심 공고는 *무엇을* 보낼지만 정하고, *어떻게* 보낼지는 모른다 | 요청 API 하나: `templateCode + userId + variables` |
| 법 준수 | 정보성과 광고성을 구분하고, 광고는 동의자에게만 | 템플릿 카테고리 3종, 수신 동의 기록, "(광고)" 자동 표기, 21~08시 발송 금지 |
| 신뢰성 | 서버가 죽거나 채널이 실패해도 알림이 빠지거나 두 번 가지 않는다 | 아웃박스 + `dedupe_key` unique + 재시도(3회) |
| 장애 격리 | 메일 업체 장애가 가입·추천 응답을 막지 않는다 | 요청은 적어 두기만(수 ms), 발송은 1분 주기 비동기. 알림센터가 죽어도 메인은 503 응답/다음 주기 재시도 |
| 확장 | 채널을 더해도 요청하는 쪽은 바뀌지 않는다 | `NotificationChannel` 구현체만 추가 (알림톡 예정) |

**분리 방식:** 별도 Spring Boot 앱 + 별도 DB(`zipick_notification`) + 같은 EC2(컨테이너 하나 추가). 비교한 대안: 같은 서버 안 모듈(빠르지만 장애 격리가 없음), 별도 서버 + 메시지 큐(공고 170건·사용자 수십 명 규모에는 운영 부담이 더 큼). 큐는 요청량이 늘면 아웃박스 앞에 붙이면 되고 요청 API 는 그대로다.

## 구조

```
메인 서버 (zipick-backend)                         알림센터 (zipick-notification, :8081)
 ├ 인증 (가입 인증·비밀번호 변경)   ─┐                ├ 큐 리스너  notification.requests 를 꺼내 아웃박스에 적음
 ├ 관심 공고 마감 D-day 스케줄러    ─┼─ RabbitMQ ─▶   ├ 템플릿     notification_templates  {{변수}} 치환
 ├ 새 공고 역매칭 스케줄러          ─┘ (던지고 끝)     ├ 수신 설정   subscriptions           주소·정보성 on/off·광고 동의(시각)
 │                                                   ├ 아웃박스   notification_messages   PENDING→SENT/FAILED/SKIPPED/CANCELLED
 └ 알림 설정 화면 (프록시)          ◀─ HTTP ──────▶   ├ 예약 발송   campaigns               시각에 대상 풀어 아웃박스로
   (조회·저장·테스트 발송: 응답 필요)  X-Internal-Key  └ 채널       Slack webhook / Email(SMTP 또는 SES) / (알림톡 예정)
```

**두 가지 길.** 응답이 필요 없는 "알림 요청"은 **RabbitMQ 큐**로 던진다 — 알림센터가 꺼져 있어도 요청이 브로커에 남고, 켜지면 이어서 처리된다.
응답이 필요한 조회·설정·테스트 발송은 **HTTP** 다. 큐가 꺼져 있거나(`NOTIFICATION_QUEUE_ENABLED=false`) 브로커가 발행을 확인하지 않으면 요청도 HTTP 로 보낸다. 제품 비교와 측정은 설계 문서의 "알림 전달 방식: 큐 도입 검토" 섹션.

## 템플릿 카테고리와 규칙

| 카테고리 | 예 | 누구에게 | 추가 규칙 |
| --- | --- | --- | --- |
| TRANSACTIONAL | EMAIL_VERIFY, PASSWORD_CHANGED, TEST | 요청에 적힌 주소. 계정·동의와 무관 | 없음 |
| INFORMATIONAL | DEADLINE, NEW_MATCH | 알림 설정이 켜진(enabled) 사용자 | 꺼져 있으면 SKIPPED |
| MARKETING | NEWSLETTER | 광고 수신 동의자 | 제목 앞 "(광고)" 자동, 21:00~08:00 은 다음 08:00 으로 미룸, 캠페인 대상은 MARKETING_CONSENTED 만 허용 |

문구는 알림센터 DB 에만 있다. 기본 템플릿은 코드가 없을 때만 들어가므로(`TemplateSeeder`) 운영자가 고친 문구를 재시작이 덮어쓰지 않는다.

## 큐 (RabbitMQ)

| 항목 | 값 |
| --- | --- |
| 큐 | `notification.requests` (durable, 우선순위 0~9). 실패 큐 `notification.requests.dlq` |
| 본문 | HTTP `POST /internal/notifications` 와 같은 JSON (`templateCode, userId, email, slackWebhookUrl, variables, dedupeKey, scheduledFor`) |
| 우선순위 | 메인 서버가 정한다: 거래성(인증 메일) 9 > 정보성(마감·새 공고) 5 > 광고 1. 큰 값이 먼저 |
| 발행 | 영속(persistent) + 발행 확인(publisher confirm, 2초). 확인이 없으면 메인 서버가 HTTP 로 대신 보낸다 |
| 소비 | 알림센터가 prefetch 10, 수동 ack. 적으면 ack / 요청 형식 오류(없는 템플릿 등)는 실패 큐 / DB 장애 같은 일시 오류는 1초 뒤 requeue |
| 중복 | at-least-once 라 같은 메시지가 두 번 올 수 있다. `dedupeKey` 가 있으면 아웃박스가 걸러 준다 (메인 서버 요청은 전부 키가 있다) |
| 선언 | 메인 서버(`NotificationQueueConfig`)와 알림센터(`internal/queue`)가 **같은 인자**로 선언한다. 다르면 브로커가 거부하므로 한쪽을 바꾸면 다른 쪽도 바꾼다 |
| 구독자 목록 | 스케줄러는 "누구에게 보낼지"를 HTTP 로 받는다. 알림센터가 닿지 않으면 마지막으로 받은 목록으로 진행한다(요청은 큐에 남으니까). 기동 직후라 받은 적이 없으면 그 주기는 건너뛴다 |

## API (`/internal/**`, 헤더 `X-Internal-Key: <NOTIFICATION_INTERNAL_KEY>`)

| Method | URI | 설명 |
| --- | --- | --- |
| POST | /internal/notifications | 알림 요청 `{templateCode, userId?, email?, slackWebhookUrl?, variables, dedupeKey?, scheduledFor?}`. 같은 dedupeKey 면 `duplicate=true` 로 기존 건 |
| GET | /internal/notifications?userId= | 사용자의 알림 내역 50건 |
| GET, PUT | /internal/subscriptions/{userId} | 수신 설정 (null=유지, ""=삭제) |
| GET | /internal/subscriptions | 정보성 알림이 켜진 사용자 전체 (메인 스케줄러용) |
| POST | /internal/subscriptions/{userId}/test | 테스트 발송 (재시도 없이 결과 즉시) |
| GET, PUT | /internal/templates[/{code}] | 템플릿 조회·수정. `POST /{code}/preview` 로 변수 넣어 미리보기 |
| GET, POST | /internal/campaigns | 예약 발송 등록 `{name, templateCode, variables, audience, userIds?, scheduledAt?}` / 목록(결과 집계) |
| DELETE | /internal/campaigns/{id} | 취소 (아직 안 나간 메시지도 취소) |
| GET | /health | 키 없이 열림 (헬스체크) |

API 문서는 이 표가 전부다 (Go 버전은 Swagger 를 두지 않았다). 각 핸들러 파일의 주석에 요청·응답 모양이 있다.

## 메인 서버에서 쓰는 법

```java
// 인증 파트(한채희): 가입 인증 메일 — 계정이 아직 없으니 이메일로 직접
notificationClient.enqueue(NotificationRequest.toEmail("EMAIL_VERIFY", email, Map.of("code", code, "expiresInMinutes", 10)));

// 비밀번호 변경 안내 — 사용자의 수신 주소는 알림센터가 안다
notificationClient.enqueue(NotificationRequest.forUser("PASSWORD_CHANGED", userId, Map.of("changedAt", now), "PWCHANGE:" + userId + ":" + now));
```

알림센터가 꺼져 있으면 `BusinessException(NOTIFICATION_UNAVAILABLE)`(503). 응답을 막으면 안 되는 곳은 잡아서 로그만 남긴다.

## 코드 구조 (Java 팀원용)

Go 는 계층(controller/service/repository) 대신 **기능별 패키지**로 나눈다. 한 패키지 안에 모델·SQL·HTTP 핸들러가 함께 있다. 자세한 표는 `zipick-notification/README.md`.

| 경로 | 역할 |
| --- | --- |
| `cmd/server/main.go` | 진입점. 설정 → DB → 채널 → 서비스 → 라우터 → 스케줄러를 손으로 조립 (Spring 의 자동 주입 대신) |
| `internal/httpx` | 응답 형식 `{success,data,error}`, 오류 코드, X-Internal-Key 미들웨어 |
| `internal/template` | 템플릿 모델·SQL·`{{변수}}` 치환·기본 템플릿 시더·핸들러 |
| `internal/subscription` | 수신 주소·동의 |
| `internal/channel` | `Channel` 인터페이스 + 슬랙 / SMTP / SES |
| `internal/message` | 아웃박스: 접수(enqueue), 발송(dispatch, `FOR UPDATE SKIP LOCKED`), 테스트 발송, 야간 금지 |
| `internal/campaign` | 예약 발송 |
| `internal/scheduler` | 1분 주기 고루틴 |
| `internal/queue` | RabbitMQ 리스너 (메인 서버가 던진 요청 → 아웃박스). 연결이 끊기면 5초마다 재접속 |

Go 를 처음 보는 팀원을 위해 각 파일 머리와 Java 와 다른 관용구(error 반환, context, 포인터로 null 표현, 인터페이스 자동 구현)에 주석을 달아 두었다.

## 운영 (로컬)

```bash
# DB 한 번
docker exec zipick-dev-postgres psql -U zipick -d postgres -c "CREATE DATABASE zipick_notification OWNER zipick;"
docker exec -i zipick-dev-postgres psql -U zipick -d zipick_notification -f - < zipick-notification/db/init.sql
# RabbitMQ 한 번 (큐를 쓰지 않으면 생략. 그러면 HTTP 로만 받는다)
docker run -d --name zipick-dev-rabbitmq -p 5672:5672 -p 15672:15672 -e RABBITMQ_DEFAULT_USER=zipick -e RABBITMQ_DEFAULT_PASS=<비밀번호> rabbitmq:4.1-management
# 실행 (Go 1.25). DB_USERNAME, DB_PASSWORD, NOTIFICATION_INTERNAL_KEY, RABBITMQ_URL=amqp://zipick:<비밀번호>@localhost:5672/
#   → 메인 서버에도 같은 NOTIFICATION_INTERNAL_KEY 와 NOTIFICATION_QUEUE_ENABLED=true, RABBITMQ_USER/PASSWORD
cd zipick-notification && go run ./cmd/server
# 테스트
go test ./...
```

Go 가 없으면 Docker 로 빌드해서 띄운다 (`golang:1.25-alpine`). 환경 변수 목록은 `internal/config/config.go` 한 곳에 있다.
이메일은 `NOTIFICATION_EMAIL_PROVIDER=smtp|ses` 와 `NOTIFICATION_EMAIL_FROM` 을 넣어야 켜진다. SES 는 처음에 샌드박스라 인증된 주소로만 보낼 수 있다.

## 다음 (3주차 공동 작업)

- 카카오 알림톡 채널 (비즈뿌리오 테스트 연동, 정보성 템플릿만) — `NotificationChannel` 구현체 하나
- 운영자 화면 (템플릿 편집·캠페인 등록·발송 결과)
- 인증 파트의 가입 인증·비밀번호 변경 메일을 `NotificationClient` 로 교체 (한채희)
- ~~여러 대로 늘리면 스케줄러에 잠금~~ → Go 버전에서 `FOR UPDATE SKIP LOCKED` 로 적용됨
