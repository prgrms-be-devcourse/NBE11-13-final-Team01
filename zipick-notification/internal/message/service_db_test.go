package message

import (
	"context"
	"testing"
	"time"

	"github.com/zipick/notification/internal/channel"
	"github.com/zipick/notification/internal/config"
	"github.com/zipick/notification/internal/db"
	"github.com/zipick/notification/internal/testdb"
)

// 실제 Postgres 로 아웃박스 흐름을 확인한다 (NOTIFICATION_TEST_DB_URL 이 있을 때만).
func dbService(t *testing.T, chs ...channel.Channel) *Service {
	pool := testdb.Pool(t)
	return NewService(NewRepo(pool), testTemplates(), chs, config.Config{MaxAttempts: 2, RetentionDays: 90})
}

func TestEnqueueDedupeAndDispatch(t *testing.T) {
	email, slack := &fakeChannel{name: "email"}, &fakeChannel{name: "slack", fail: true}
	svc := dbService(t, email, slack)
	ctx := context.Background()
	req := Request{Source: "main", TemplateCode: "MAIN_TEST", Channels: []string{"email", "slack"},
		To:        Recipient{Email: str("a@b.co"), SlackWebhookURL: str("https://hooks.slack.com/x")},
		Variables: map[string]any{"name": "지우"}, DedupeKey: str("k1"), Tags: map[string]string{"user": "2"}}
	first, err := svc.Enqueue(ctx, req)
	if err != nil {
		t.Fatal(err)
	}
	if first.Status != Pending || len(first.Deliveries) != 2 || first.Duplicate {
		t.Fatalf("%+v", first)
	}
	again, _ := svc.Enqueue(ctx, req)
	if !again.Duplicate || again.ID != first.ID {
		t.Fatalf("같은 dedupeKey 는 기존 건: %+v", again)
	}

	// 1회차: 이메일 성공, 슬랙 실패(attempts 1, 아직 PENDING)
	if _, err := svc.DispatchPending(ctx); err != nil {
		t.Fatal(err)
	}
	got, _ := svc.Get(ctx, first.ID)
	if got.Status != Pending || got.Deliveries[0].Status != DSent || got.Deliveries[1].Status != DPending || got.Deliveries[1].Attempts != 1 {
		t.Fatalf("1회차: %+v", got.Deliveries)
	}
	if len(email.sent) != 1 || email.sent[0].Subject != "[ZIPICK] 테스트 지우" {
		t.Fatalf("이메일 문구: %+v", email.sent)
	}
	// 2회차: 슬랙 두 번째 실패 → FAILED, 이메일로는 다시 보내지 않음 → PARTIAL
	if _, err := svc.DispatchPending(ctx); err != nil {
		t.Fatal(err)
	}
	got, _ = svc.Get(ctx, first.ID)
	if got.Status != Partial || got.Deliveries[1].Status != DFailed || len(email.sent) != 1 {
		t.Fatalf("2회차: status=%s deliveries=%+v emailSent=%d", got.Status, got.Deliveries, len(email.sent))
	}

	list, err := svc.Search(ctx, map[string]string{"user": "2"}, "")
	if err != nil || len(list) != 1 || list[0].ID != first.ID {
		t.Fatalf("tag 조회: %v %+v", err, list)
	}
	if none, _ := svc.Search(ctx, map[string]string{"user": "3"}, ""); len(none) != 0 {
		t.Fatal("다른 태그는 나오면 안 됨")
	}
}

func TestDeliveryNow(t *testing.T) {
	email := &fakeChannel{name: "email"}
	svc := dbService(t, email)
	res, err := svc.Enqueue(context.Background(), Request{Source: "main", TemplateCode: "MAIN_TEST", Channels: []string{"email"},
		To: Recipient{Email: str("a@b.co")}, Delivery: "now"})
	if err != nil {
		t.Fatal(err)
	}
	if res.Status != Sent || res.Deliveries[0].SentAt == nil || len(email.sent) != 1 {
		t.Fatalf("now 는 바로 보내고 결과까지: %+v", res)
	}
}

func TestScheduledIsNotSentEarly(t *testing.T) {
	email := &fakeChannel{name: "email"}
	svc := dbService(t, email)
	later := db.LocalDateTime{Time: db.Now().Add(time.Hour)}
	res, _ := svc.Enqueue(context.Background(), Request{Source: "main", TemplateCode: "MAIN_TEST", Channels: []string{"email"},
		To: Recipient{Email: str("a@b.co")}, ScheduledAt: &later})
	if _, err := svc.DispatchPending(context.Background()); err != nil {
		t.Fatal(err)
	}
	if got, _ := svc.Get(context.Background(), res.ID); got.Status != Pending || len(email.sent) != 0 {
		t.Fatalf("예약 건은 아직: %+v", got)
	}
}

func TestBatchCreateCancelAndMask(t *testing.T) {
	email := &fakeChannel{name: "email"}
	svc := dbService(t, email)
	ctx := context.Background()
	later := db.LocalDateTime{Time: db.Now().Add(time.Hour)}
	b, err := svc.CreateBatch(ctx, BatchRequest{Source: "main", TemplateCode: "MAIN_TEST", Channels: []string{"email"}, ScheduledAt: &later,
		Tags: map[string]string{"campaign": "7"}, Recipients: []BatchRecipient{
			{To: Recipient{Email: str("a@b.co")}, DedupeKey: str("c7-a"), Tags: map[string]string{"user": "1"}},
			{To: Recipient{Email: str("b@b.co")}, DedupeKey: str("c7-b"), Tags: map[string]string{"user": "2"}},
			{To: Recipient{Email: str("a@b.co")}, DedupeKey: str("c7-a")}, // 중복 → 건너뜀
		}})
	if err != nil {
		t.Fatal(err)
	}
	if b.Total != 2 || b.Pending != 2 || b.Skipped != 1 || b.Status != "SCHEDULED" {
		t.Fatalf("%+v", b)
	}
	if byUser, _ := svc.Search(ctx, map[string]string{"user": "2", "campaign": "7"}, ""); len(byUser) != 1 {
		t.Fatalf("수신자 tags 는 묶음 tags 와 합쳐진다: %+v", byUser)
	}
	// 수신자 하나가 잘못되면 전체 거부
	if _, err := svc.CreateBatch(ctx, BatchRequest{Source: "main", TemplateCode: "MAIN_TEST", Channels: []string{"email"},
		Recipients: []BatchRecipient{{To: Recipient{Email: str("ok@b.co")}}, {To: Recipient{}}}}); err == nil {
		t.Fatal("주소 없는 수신자가 있으면 거부돼야 함")
	}
	c, err := svc.CancelBatch(ctx, b.ID)
	if err != nil || c.Status != "CANCELLED" || c.Cancelled != 2 || c.Pending != 0 {
		t.Fatalf("%v %+v", err, c)
	}
	if _, err := svc.DispatchPending(ctx); err != nil || len(email.sent) != 0 {
		t.Fatal("취소된 건은 나가면 안 됨")
	}
	if _, err := svc.GetBatch(ctx, 9999); err == nil {
		t.Fatal("없는 묶음은 404")
	}

	// 주소 가리기: 방금 만든 건은 보관 기간 전이라 그대로
	if n, _ := svc.MaskOldAddresses(ctx); n != 0 {
		t.Fatalf("아직 가리면 안 됨: %d", n)
	}
	if _, err := svc.repo.pool.Exec(ctx, `update notification_messages set created_at = created_at - interval '100 days'`); err != nil {
		t.Fatal(err)
	}
	if n, _ := svc.MaskOldAddresses(ctx); n != 2 {
		t.Fatalf("가린 수: %d", n)
	}
	var masked string
	_ = svc.repo.pool.QueryRow(ctx, `select to_email from notification_messages order by id limit 1`).Scan(&masked)
	if masked != "a***@b.co" {
		t.Fatalf("masked=%q", masked)
	}
	if n, _ := svc.MaskOldAddresses(ctx); n != 0 {
		t.Fatal("두 번 가리지 않음")
	}
}
