# 알림센터 (zipick-notification)

담당: 안서호 · 2026-10-20 분리 · **2026-10-23 "순수 전송" 으로 재설계** (강사 피드백: 알림센터는 비즈니스 로직 없이 템플릿을 갖고 요청받은 대로 바인딩해 보내기만 한다)

**구현 언어: Go** (표준 라이브러리 `net/http` + pgx). 처음엔 Spring 으로 만들었다가, 5개 후보를 같은 조건에서 측정한 뒤 Go 로 다시 만들었다.
측정과 결정 근거는 설계 문서의 "알림센터 언어 선택" 섹션에 있다. 요약: 발송 속도는 언어 차이가 없었고(외부 API 응답 시간이 정함), 메모리가 Go 9MB vs Spring 164~204MB 로 EC2 한 대(1.8GB)를 메인 서버·브로커와 나눠 쓰는 조건에서 Go 가 유리했다.

**API 계약은 `docs/notification-api.md` (스펙 v1)** 가 원본이다. 이 문서는 왜 이렇게 나눴고 안이 어떻게 생겼는지를 적는다.

## 알림센터가 하는 일과 하지 않는 일

| 알림센터가 한다 | 알림센터가 **하지 않는다** (요청하는 쪽이 한다) |
| --- | --- |
| 요청 검사 (템플릿·채널·주소 형식) | 누구에게 보낼지 (사용자 목록, 알림 설정) |
| 템플릿(`templates/*.yaml`)에 변수 바인딩 | 보내도 되는지 (알림 on/off, 광고 수신 동의) |
| 아웃박스에 적고 때가 되면 채널별 발송·재시도 | 언제 보낼지 (광고 21~08시 회피, 예약 시각) |
| 같은 `dedupeKey` 한 번만, 묶음 취소, 90일 뒤 주소 가림 | 캠페인 대상 선정·운영자 화면 |
| 결과 기록 (채널별 상태, 빠진 변수) | 문구 수정 (파일 → PR) |

이전 버전(10/20)은 수신 설정·동의·카테고리별 규칙·캠페인 대상 선정까지 알림센터가 가졌다. 재설계에서 그 판단을 전부 **메인 서버로 되돌렸다** (`notification_settings` 는 메인 DB 로 복귀, 캠페인은 메인의 `campaigns` + 운영자 API). 알림센터는 이제 어떤 서비스가 던져도 같은 스펙으로 받는 **전송 부품**이다.

## 왜 분리했나

| 기준 | 뜻 | 설계에 반영된 것 |
| --- | --- | --- |
| 분리 | 인증·추천·관심 공고는 *무엇을 누구에게* 보낼지만 정하고, *어떻게*(채널 구현·재시도) 는 모른다 | 요청 API 하나: `source + templateCode + channels + to + variables` |
| 법 준수 | 정보성과 광고성을 구분하고, 광고는 동의자에게만 | 요청하는 쪽의 책임: 메인 서버가 동의·시각(`MarketingWindow`)을 판단. 문구의 "(광고)"·수신 거부 안내는 템플릿에 |
| 신뢰성 | 서버가 죽거나 채널이 실패해도 알림이 빠지거나 두 번 가지 않는다 | 아웃박스 + `dedupe_key` unique + 재시도(3회) |
| 장애 격리 | 메일 업체 장애가 가입·추천 응답을 막지 않는다 | 요청은 적어 두기만(수 ms), 발송은 1분 주기 비동기. 알림센터가 죽어도 메인은 503 응답/다음 주기 재시도 |
| 확장 | 채널을 더해도 요청하는 쪽은 바뀌지 않는다 | `channel.Channel` 구현체만 추가 (알림톡 예정) |
| 재사용 | 인증·수집기 등 어떤 서비스도 같은 스펙으로 쓴다 | 스펙 문서(`notification-api.md`) + 견본(수집기 일일 보고 `CollectorReportNotifier`) |

**분리 방식:** 별도 앱(Go) + 별도 DB(`zipick_notification`) + 같은 EC2(컨테이너 하나 추가). 비교한 대안: 같은 서버 안 모듈(빠르지만 장애 격리가 없음), 별도 서버 + 메시지 큐(공고 170건·사용자 수십 명 규모에는 운영 부담이 더 큼). 큐는 요청량이 늘면 아웃박스 앞에 붙이면 되고 요청 API 는 그대로다.

## 구조

```
요청하는 서비스                                      알림센터 (zipick-notification, :8081)
 메인 서버 (zipick-backend)                           ├ 큐 리스너   notification.requests.v1 → 아웃박스
 ├ 알림 설정  notification_settings (메인 DB)   ─┐     ├ 템플릿      templates/*.yaml  (파일, 시작 때 읽음) {{변수}} 바인딩
 ├ 관심 공고 마감 D-day 스케줄러                ─┼─ RabbitMQ ─▶ ├ 아웃박스    notification_messages    PENDING→SENT/PARTIAL/FAILED/CANCELLED
 ├ 새 공고 역매칭 스케줄러                      ─┘ (던지고 끝)  ├ 채널별 기록 notification_deliveries  메시지 × 채널, 채널마다 재시도
 ├ 캠페인(대상 선정·야간 회피) campaigns   ─── HTTP 묶음 ──▶   ├ 묶음        notification_batches     취소·집계
 ├ 테스트 발송 (delivery: now) / 내역 (tag)  ◀── HTTP ────▶   ├ 채널        Slack webhook / Email(SMTP 또는 SES) / (알림톡 예정)
 공고 수집기 ─ 일일 보고 ─────────────────────── 큐/HTTP ──▶   └ 1분 주기    때가 된 메시지 발송, 하루 1회 90일 지난 주소 가림
 인증 ─ 가입 인증 메일 (예정) ────────────────── 큐/HTTP ──▶
```

**두 가지 길.** 응답이 필요 없는 "알림 요청"은 **RabbitMQ 큐**로 던진다 — 알림센터가 꺼져 있어도 요청이 브로커에 남고, 켜지면 이어서 처리된다.
결과가 바로 필요한 테스트 발송(`delivery: now`)·내역·묶음은 **HTTP** 다. 큐가 꺼져 있거나(`NOTIFICATION_QUEUE_ENABLED=false`) 브로커가 발행을 확인하지 않으면 요청도 HTTP 로 보낸다. 제품 비교와 측정은 설계 문서의 "알림 전달 방식: 큐 도입 검토" 섹션.

## 템플릿 (파일)

문구는 저장소의 `zipick-notification/templates/*.yaml` 에 있다 (코드·담당자·변수·채널별 문구). 바꾸려면 PR → 배포. 알림센터는 시작할 때 전부 읽고, 코드 형식이 틀리거나 중복이면 뜨지 않는다.
이전처럼 DB 에 두고 API 로 고치는 방식은 버렸다: 문구도 코드처럼 리뷰·이력이 남는 편이 낫고, 운영자 편집 화면을 따로 만들 필요가 없다. 템플릿 목록과 변수는 스펙 문서 4절.

변수가 빠진 요청은 **거부하지 않고 빈칸으로 보낸다** (응답 `missingVariables` + 경고 로그). 마감 알림이 변수 하나 때문에 안 가는 것보다 낫다고 봤다.

## 큐 (RabbitMQ)

| 항목 | 값 |
| --- | --- |
| 큐 | `notification.requests.v1` (durable, 우선순위 0~9). 실패 큐 `notification.requests.v1.dlq`. 스펙이 바뀌어 이름에 v1 을 붙였다 (이전 `notification.requests` 는 브로커에서 지운다) |
| 본문 | HTTP `POST /internal/v1/notifications` 와 같은 JSON (`source, templateCode, channels, to, variables, dedupeKey, scheduledAt, tags`) |
| 우선순위 | 메인 서버가 정한다: 거래성(인증 메일) 9 > 정보성(마감·새 공고) 5 > 광고 1. 큰 값이 먼저 |
| 발행 | 영속(persistent) + 발행 확인(publisher confirm, 2초). 확인이 없으면 메인 서버가 HTTP 로 대신 보낸다 |
| 소비 | 알림센터가 prefetch 10, 수동 ack. 적으면 ack / 요청 형식 오류(없는 템플릿 등)는 실패 큐 / DB 장애 같은 일시 오류는 1초 뒤 requeue |
| 중복 | at-least-once 라 같은 메시지가 두 번 올 수 있다. `dedupeKey` 가 있으면 아웃박스가 걸러 준다 (메인 서버 요청은 전부 키가 있다) |
| 선언 | 메인 서버(`NotificationQueueConfig`)와 알림센터(`internal/queue`)가 **같은 인자**로 선언한다. 다르면 브로커가 거부하므로 한쪽을 바꾸면 다른 쪽도 바꾼다 |
| 누구에게 | 스케줄러가 메인 DB 의 `notification_settings` 에서 직접 고른다 (알림 on + 주소 있음). 알림센터에 물어볼 것이 없어 알림센터가 꺼져 있어도 주기가 돈다 |

## API

전체 스펙은 **`docs/notification-api.md`**. 요약: `POST /internal/v1/notifications`(요청), `GET …/{id}`, `GET …?tag=user:2`(조회), `POST|GET|DELETE /internal/v1/batches[/{id}]`(묶음), `GET /internal/v1/templates[/{code}]` + `POST …/{code}/preview`(읽기 전용), `GET /internal/v1/channels`(켜진 채널), `GET /health`.

## 메인 서버에서 쓰는 법

```java
// 인증 파트(한채희): 가입 인증 메일 — 계정이 아직 없으니 이메일로 직접. 큐로 던지고 끝 (큐가 없으면 HTTP)
notificationClient.request(NotificationRequest.toEmail("AUTH_EMAIL_VERIFY", email,
        Map.of("name", name, "code", code, "expiresInMinutes", 10)), NotificationPriority.HIGH);

// 사용자의 알림 설정(주소·채널)으로 — 설정은 메인 DB 에 있다
NotificationSetting s = settingRepository.findById(userId).orElseThrow();
if (!s.channels().isEmpty())
    notificationClient.request(NotificationRequest.toUser("AUTH_PASSWORD_CHANGED", s, Map.of("changedAt", now, "appUrl", appUrl),
            "PWCHANGE:" + userId + ":" + now), NotificationPriority.HIGH);
```

알림센터가 꺼져 있고 큐도 없으면 `BusinessException(NOTIFICATION_UNAVAILABLE)`(503). 응답을 막으면 안 되는 곳은 잡아서 로그만 남긴다.

### 메인 서버 쪽 구성 (알림센터에서 돌아온 판단)

| 위치 | 역할 |
| --- | --- |
| `notification/domain/NotificationSetting` + `notification_settings` | 알림 on/off, 슬랙·이메일 주소, 마감 일수, 새 공고 여부, 광고 동의(+시각) |
| `notification/service/NotificationService` | 설정 화면 뒷단. 테스트 발송은 저장된 주소로 `delivery: now`, 내역은 `tag=user:{id}` |
| `notification/service/NotificationScheduler` | 마감·새 공고를 찾아 설정의 주소·채널로 요청 (dedupeKey 로 중복 없음) |
| `notification/service/MarketingWindow` | 광고 21~08시 회피 (다음 08:00) |
| `notification/campaign/*` + `campaigns` | 소식 메일 캠페인: 동의자 대상 선정 → 채널 조합별 묶음 요청 → 집계·취소. `/api/v1/admin/campaigns` (ROLE_ADMIN), 화면 `/admin/campaigns` |
| `notification/service/CollectorReportNotifier` | 공고 수집 일일 보고 — 다른 서비스가 스펙대로 던지는 견본 |

## 코드 구조 (Java 팀원용)

Go 는 계층(controller/service/repository) 대신 **기능별 패키지**로 나눈다. 한 패키지 안에 모델·SQL·HTTP 핸들러가 함께 있다. 자세한 표는 `zipick-notification/README.md`.

| 경로 | 역할 |
| --- | --- |
| `cmd/server/main.go` | 진입점. 설정 → DB → 채널 → 서비스 → 라우터 → 스케줄러를 손으로 조립 (Spring 의 자동 주입 대신) |
| `internal/httpx` | 응답 형식 `{success,data,error}`, 오류 코드, X-Internal-Key 미들웨어 |
| `internal/template` | yaml 템플릿 읽기·검증, `{{변수}}` 바인딩(채널별), 읽기 전용 핸들러 |
| `internal/channel` | `Channel` 인터페이스 + 슬랙 / SMTP / SES. 채널은 받은 주소로 받은 문구를 보낼 뿐 |
| `internal/message` | 요청 검사 → 바인딩 → 아웃박스 적기 → 채널별 발송(`FOR UPDATE SKIP LOCKED`)·재시도, `delivery: now`, tag 조회, 묶음, 주소 가림 |
| `internal/scheduler` | 1분 주기 고루틴 (발송 + 하루 1회 주소 가림) |
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

Go 가 없으면 Docker 로 빌드해서 띄운다 (`golang:1.25-alpine`). 환경 변수 목록은 `internal/config/config.go` 한 곳에 있다 (`NOTIFICATION_TEMPLATES_DIR` 기본 `./templates`, 이미지 안에서는 `/templates`; `NOTIFICATION_RETENTION_DAYS` 기본 90).
메인 서버의 `db/local-init.sql` 에 `notification_settings`·`campaigns` 가 추가됐다 (이미 있는 로컬 DB 는 그 두 테이블만 만든다). 알림센터 DB 는 스키마가 바뀌어 `db/init.sql` 을 다시 적용한다 (기록이 지워진다).
이메일은 `NOTIFICATION_EMAIL_PROVIDER=smtp|ses` 와 `NOTIFICATION_EMAIL_FROM` 을 넣어야 켜진다. SES 는 처음에 샌드박스라 인증된 주소로만 보낼 수 있다.

## 다음 (3주차 공동 작업)

- 카카오 알림톡 채널 (비즈뿌리오 테스트 연동) — `channel.Channel` 구현체 하나 + 템플릿에 `kakao:` 문구
- 인증 파트의 가입 인증·비밀번호 변경 메일을 `NotificationClient` 로 교체 (한채희) — 스펙 문서 8절
- 수집기 일일 보고의 운영 슬랙 주소 설정 (`NOTIFICATION_OPS_SLACK_WEBHOOK_URL`) 후 실제 발송 확인
- ~~운영자 화면 (템플릿 편집·캠페인 등록·발송 결과)~~ → 템플릿은 파일, 캠페인 화면은 메인 `/admin/campaigns` 로 구현됨
- ~~여러 대로 늘리면 스케줄러에 잠금~~ → Go 버전에서 `FOR UPDATE SKIP LOCKED` 로 적용됨
