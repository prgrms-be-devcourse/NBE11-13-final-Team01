# 알림센터 API 스펙 v1

담당: 안서호 · 2026-10-23 · 강사 피드백("알림센터는 비즈니스 로직 없이 순수 전송만. 템플릿을 갖고 요청받은 대로 바인딩해 보낸다") 반영

알림센터(`zipick-notification`, 포트 8081)는 **판단하지 않는다.** 누구에게·어느 채널로·언제·보내도 되는지는 **요청하는 서비스**가 정해서 보낸다.
알림센터가 하는 일은 네 가지뿐이다: ① 요청 검사 ② 템플릿에 변수 바인딩 ③ 적어 두고(아웃박스) 때가 되면 채널별로 보내기(재시도) ④ 결과 기록.

이 문서는 메인 서버(알림 스케줄러·캠페인)·인증(가입 인증 메일)·공고 수집기(일일 보고) 등 **알림센터를 쓰는 모든 쪽**이 지키는 계약이다.

## 1. 공통

| 항목 | 값 |
| --- | --- |
| 인증 | 모든 `/internal/**` 에 헤더 `X-Internal-Key: <NOTIFICATION_INTERNAL_KEY>` (서버끼리 공유하는 키). `/health` 만 열려 있다 |
| 응답 형식 | `{"success": true, "data": …, "error": null}` / `{"success": false, "data": null, "error": {"code": "…", "message": "…"}}` |
| 시각 | `yyyy-MM-ddTHH:mm:ss` (서울 벽시계, Java `LocalDateTime` 과 같은 형식). 시간대 표기 없음 |
| 오류 코드 | `INVALID_INPUT` 400 · `NO_RECIPIENT` 400 (고른 채널의 주소가 없음) · `CHANNEL_UNAVAILABLE` 400 (서버에 그 채널이 꺼져 있음) · `TEMPLATE_NOT_FOUND` 404 · `MESSAGE_NOT_FOUND` 404 · `BATCH_NOT_FOUND` 404 · `AUTH_REQUIRED` 401 |

## 2. 두 가지 길: 큐와 HTTP

| | RabbitMQ 큐 | HTTP |
| --- | --- | --- |
| 언제 | 응답이 필요 없는 알림 요청 (마감·새 공고·인증 메일·일일 보고) | 결과가 바로 필요할 때 (테스트 발송 `delivery: now`), 조회, 묶음 |
| 어디로 | 큐 `notification.requests.v1` (durable, 우선순위 0~9, persistent + publisher confirm). 실패 큐 `notification.requests.v1.dlq` | `POST /internal/v1/notifications` |
| 본문 | **HTTP 와 같은 JSON** (아래 3절) | |
| 장점 | 알림센터가 꺼져 있어도 요청이 브로커에 남는다 | 400 을 바로 받아 실수를 금방 안다 |
| 실패 | 요청 형식 오류(없는 템플릿·주소 없음)는 실패 큐로. DB 장애 같은 일시 오류는 1초 뒤 requeue | 알림센터가 꺼져 있으면 연결 실패 |

큐를 선언하는 쪽(메인 `NotificationQueueConfig`, 알림센터 `internal/queue`)은 **같은 인자**를 써야 한다. 다르면 브로커가 거부한다.

## 3. 알림 요청 — `POST /internal/v1/notifications` (큐 본문도 동일)

```json
{
  "source": "main",
  "templateCode": "NOTICE_DEADLINE_REMINDER",
  "channels": ["email", "slack"],
  "to": { "email": "me@example.com", "slackWebhookUrl": "https://hooks.slack.com/services/..." },
  "variables": { "dDayLabel": "D-3", "noticeTitle": "구미 행복주택", "detailUrl": "https://..." },
  "dedupeKey": "DEADLINE:2:10:3",
  "scheduledAt": "2026-10-24T08:00:00",
  "tags": { "user": "2" },
  "delivery": null
}
```

| 필드 | 필수 | 설명 |
| --- | --- | --- |
| `source` | ✔ | 요청한 서비스. 소문자·숫자·`-` 1~40자 (`main`, `auth`, `collector`). 조회·로그에 쓴다 |
| `templateCode` | ✔ | `templates/*.yaml` 의 `code` (4절). 없으면 404 |
| `channels` | ✔ | `email`, `slack` 중 하나 이상. 템플릿에 그 채널 문구가 있어야 하고, 서버에 그 채널이 켜져 있어야 하며(아니면 `CHANNEL_UNAVAILABLE`), `to` 에 그 주소가 있어야 한다(아니면 `NO_RECIPIENT`) |
| `to.email` | 채널에 따라 | 이메일 주소 |
| `to.slackWebhookUrl` | 채널에 따라 | `https://hooks.slack.com/` 으로 시작해야 한다 (다른 주소로 요청을 보내는 SSRF 방지) |
| `variables` | | 템플릿 변수. **빠진 변수는 거부하지 않고 빈칸으로 보낸다.** 응답의 `missingVariables` 로 알려 주고 경고 로그를 남긴다 |
| `dedupeKey` | | 1~120자. 같은 키는 **한 번만 적힌다** — 두 번째 요청은 기존 건을 `duplicate: true` 로 돌려준다. 스케줄러처럼 같은 일을 다시 볼 수 있는 쪽은 꼭 넣는다 |
| `scheduledAt` | | 비우면 바로. 예약 시각이 지났으면 다음 주기에 바로 나간다 |
| `tags` | | 알림센터가 해석하지 않는 꼬리표(20개 이하). 조회에 쓴다. 메인 서버는 `user:{id}` 를 넣어 사용자별 내역을 본다 |
| `delivery` | | `"now"` 면 기다리지 않고 바로 한 번 보내고(재시도 없음) 채널별 결과를 응답에 담는다. 테스트 발송용. HTTP 로만 의미 있다 |

**응답** (`GET /internal/v1/notifications/{id}` 도 같은 모양)

```json
{
  "id": 17, "source": "main", "templateCode": "NOTICE_DEADLINE_REMINDER", "batchId": null,
  "channels": ["email", "slack"], "title": "[ZIPICK] 접수 마감 D-3 · 구미 행복주택",
  "status": "PENDING",
  "deliveries": [
    { "channel": "email", "status": "PENDING", "attempts": 0, "lastError": null, "sentAt": null },
    { "channel": "slack", "status": "PENDING", "attempts": 0, "lastError": null, "sentAt": null }
  ],
  "missingVariables": [], "tags": { "user": "2" },
  "scheduledAt": "2026-10-23T14:00:00", "createdAt": "2026-10-23T14:00:00", "duplicate": false
}
```

메시지 `status`: `PENDING`(안 보낸 채널이 있음) → `SENT`(모든 채널 성공) / `PARTIAL`(일부만 성공) / `FAILED`(모두 실패) / `CANCELLED`(묶음 취소).
채널별 `deliveries[].status`: `PENDING` → `SENT` / `FAILED`(재시도 3회 뒤). **채널마다 따로 재시도하고, 성공한 채널로는 다시 보내지 않는다.**

**조회:** `GET /internal/v1/notifications?tag=user:2&tag=campaign:7&source=main` — tag 는 `키:값`, 여러 개면 모두 만족하는 것. 최근 50건.

## 4. 템플릿 — 저장소의 `zipick-notification/templates/*.yaml`

문구는 DB 가 아니라 **파일**에 있다. 바꾸려면 PR 을 올리고(리뷰·이력), 배포하면 알림센터가 시작할 때 읽는다. 잘못된 파일이 있으면 서버가 뜨지 않는다.

```yaml
code: NOTICE_DEADLINE_REMINDER        # 대문자·숫자·_ (예: 영역_이름)
owner: 안서호 (메인 서버)              # 문구를 책임지는 사람
variables: [dDayLabel, whenText, applyEndAt, noticeTitle, regionsText, detailUrl]   # 비워 두면 문구에서 자동 추출
email:
  subject: "[ZIPICK] 접수 마감 {{dDayLabel}} · {{noticeTitle}}"
  body: |-
    관심 공고의 접수가 {{whenText}}({{applyEndAt}}) 마감돼요.{{regionsText}}
    공고 보기: {{detailUrl}}
slack:
  text: "*접수 마감 {{dDayLabel}} · {{noticeTitle}}*\n..."
```

`email` 과 `slack` 중 필요한 것만 둔다. 요청이 템플릿에 없는 채널을 고르면 400.

| 코드 | 요청하는 쪽 | 변수 |
| --- | --- | --- |
| `MAIN_TEST` | 메인 (설정 화면 테스트 발송) | deadlineDays |
| `NOTICE_DEADLINE_REMINDER` | 메인 스케줄러 | dDayLabel, whenText, applyEndAt, noticeTitle, regionsText, detailUrl |
| `NOTICE_NEW_MATCH` | 메인 스케줄러 | noticeTitle, score, region, deadlineText, detailUrl |
| `AUTH_EMAIL_VERIFY` | 메인 가입 화면 (`EmailVerificationService`) | code, expiresInMinutes — 이메일만. 결과가 바로 필요해 `delivery: now` 로 보낸다 |
| `AUTH_PASSWORD_CHANGED` | 인증 (한채희) | changedAt, appUrl — 이메일만 |
| `MARKETING_NEWSLETTER` | 메인 캠페인 | headline, message, unsubscribeUrl — 제목에 `(광고)`, 본문에 수신 거부 안내가 문구에 들어 있다 |
| `COLLECTOR_DAILY_REPORT` | 공고 수집기 | date, collectedRows, createdNotices, updatedNotices, missingClosedNotices, synchronizedHousings, elapsedSeconds |

읽기 API: `GET /internal/v1/templates`, `GET /internal/v1/templates/{code}`, `POST /internal/v1/templates/{code}/preview` (`{"variables": {...}}` → 채널별 문구 + missingVariables). 수정 API 는 없다 (파일이 원본).

## 5. 묶음 요청 — 수신자 여러 명 (캠페인)

같은 템플릿·채널·시각으로 여러 명에게. **대상 선정은 요청하는 쪽**이 한다 (메인 서버: 알림 on + 광고 동의 + 주소 있음).

| Method | URI | 설명 |
| --- | --- | --- |
| POST | `/internal/v1/batches` | `{source, templateCode, channels, scheduledAt?, tags?, recipients: [{to, variables, dedupeKey?, tags?}]}` (1~5,000명). 수신자 하나라도 검사에 걸리면 **전체 거부** (`recipients[3]: …`). 수신자 `tags` 는 묶음 `tags` 위에 덧씌운다. 같은 dedupeKey 는 건너뛰고 `skippedDuplicates` 로 센다 |
| GET | `/internal/v1/batches/{id}` | `{id, status: SCHEDULED|CANCELLED, total, pending, sent, partial, failed, cancelled, skippedDuplicates, …}` |
| DELETE | `/internal/v1/batches/{id}` | 취소. **아직 안 나간 메시지만** 취소되고 이미 나간 것은 그대로 |

## 6. 채널 상태 — `GET /internal/v1/channels`

`{"email": true, "slack": true}`. 이메일은 알림센터의 `NOTIFICATION_EMAIL_PROVIDER` 가 `smtp`/`ses` 일 때만 켜진다. 설정 화면이 "이메일을 쓸 수 있는가" 를 이걸로 보여 준다.

## 7. 요청하는 쪽의 책임 (알림센터가 하지 않는 것)

| 일 | 누가 | 메인 서버에서의 위치 |
| --- | --- | --- |
| 알림 on/off, 주소, 마감 일수, 새 공고 여부 | 요청하는 쪽의 DB | `notification_settings` (메인 DB), `NotificationSetting` |
| 광고성 정보: 수신 동의자에게만, 동의 시각 보관 | 요청하는 쪽 | `marketing_consent`, `marketing_consented_at` |
| 광고성 정보: 21~08시 금지 | 요청하는 쪽 | `MarketingWindow.nextAllowed()` — 캠페인 예약 시각 |
| `(광고)` 표기·수신 거부 안내 | 템플릿 문구 | `MARKETING_NEWSLETTER.yaml` |
| 캠페인 대상 선정·운영자 API·화면 | 요청하는 쪽 | `campaign` 패키지, `/api/v1/admin/campaigns` (ROLE_ADMIN), `/admin/campaigns` 화면 |
| 중복 방지 키 만들기 | 요청하는 쪽 | `DEADLINE:{user}:{notice}:{d}`, `NEW_MATCH:{user}:{notice}`, `CAMPAIGN:{campaign}:{user}` |

알림센터는 발송 기록의 **수신 주소를 90일 뒤 가린다** (`a***@x.com`, 웹훅 끝 6자). 그 뒤에는 재발송할 수 없다.

## 8. 다른 서비스에서 쓰는 예

**가입 인증 메일 (`auth/service/EmailVerificationService`).** 계정이 아직 없으니 이메일을 직접 넣고, 사용자가 결과를 바로 알아야 하므로 큐 대신 HTTP `delivery: now` 로 보낸다.

```java
NotificationEventResponse sent = notificationClient.enqueue(
    NotificationRequest.toEmail("AUTH_EMAIL_VERIFY", email, Map.of("code", code, "expiresInMinutes", 10)).now());
if (!"SENT".equals(sent.status())) { /* 사용자에게 "보내지 못했다"고 알림 */ }
```

**비밀번호 변경 안내처럼 결과를 기다릴 필요가 없는 메일**은 큐로 던진다.

```java
notificationClient.request(NotificationRequest.toEmail("AUTH_PASSWORD_CHANGED", email, Map.of("changedAt", now, "appUrl", appUrl)), NotificationPriority.HIGH);
```

**공고 수집기 — 일일 보고.** `CollectorReportNotifier` 가 수집이 끝나면 운영 슬랙·메일(`NOTIFICATION_OPS_*`)로 `COLLECTOR_DAILY_REPORT` 를 요청한다. 다른 서비스가 스펙대로 요청하는 견본이다. 알림센터에 닿지 않아도 수집은 실패하지 않는다(로그만).

**curl 로 직접**

```bash
curl -s -X POST http://localhost:8081/internal/v1/notifications -H "X-Internal-Key: $KEY" -H 'Content-Type: application/json' -d '{
  "source":"collector","templateCode":"COLLECTOR_DAILY_REPORT","channels":["slack"],
  "to":{"slackWebhookUrl":"https://hooks.slack.com/services/..."},
  "variables":{"date":"2026-10-23","collectedRows":170,"createdNotices":3,"updatedNotices":12,"missingClosedNotices":1,"synchronizedHousings":120,"elapsedSeconds":41},
  "dedupeKey":"COLLECTOR_REPORT:2026-10-23","delivery":"now"}'
```
