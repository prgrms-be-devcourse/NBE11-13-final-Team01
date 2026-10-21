package message

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/zipick/notification/internal/channel"
	"github.com/zipick/notification/internal/config"
	"github.com/zipick/notification/internal/db"
	"github.com/zipick/notification/internal/httpx"
	"github.com/zipick/notification/internal/subscription"
	"github.com/zipick/notification/internal/template"
	"github.com/zipick/notification/internal/testdb"
)

// fakeChannel 은 실제로 보내지 않고 기록만 한다. fail 이 있으면 그 오류로 실패한다.
type fakeChannel struct {
	name string
	sent []channel.Message
	fail error
}

func (f *fakeChannel) Name() string { return f.name }
func (f *fakeChannel) Supports(r channel.Recipient) bool {
	if f.name == "email" {
		return r.Email != nil
	}
	return r.SlackWebhookURL != nil
}
func (f *fakeChannel) Send(_ context.Context, _ channel.Recipient, m channel.Message) error {
	if f.fail != nil {
		return f.fail
	}
	f.sent = append(f.sent, m)
	return nil
}

type env struct {
	ctx   context.Context
	pool  *pgxpool.Pool
	svc   *Service
	subs  *subscription.Repo
	slack *fakeChannel
	email *fakeChannel
}

func setup(t *testing.T) env {
	t.Helper()
	pool := testdb.Pool(t)
	ctx := context.Background()
	templates := template.NewRepo(pool)
	if err := template.Seed(ctx, templates); err != nil {
		t.Fatal(err)
	}
	subs := subscription.NewRepo(pool)
	slack, email := &fakeChannel{name: "slack"}, &fakeChannel{name: "email"}
	svc, err := NewService(NewRepo(pool), subs, templates, []channel.Channel{slack, email},
		config.Config{AppURL: "https://zipick.kr", QuietStart: "21:00", QuietEnd: "08:00", MaxAttempts: 3})
	if err != nil {
		t.Fatal(err)
	}
	return env{ctx, pool, svc, subs, slack, email}
}

func (e env) subscribe(t *testing.T, userID int64, enabled, marketing bool) {
	t.Helper()
	slackURL := "https://hooks.slack.com/services/T/B/x"
	if _, err := e.subs.Save(e.ctx, userID, subscription.Request{Enabled: enabled, SlackWebhookURL: &slackURL,
		MarketingConsent: &marketing, NewMatchEnabled: true}); err != nil {
		t.Fatal(err)
	}
}

func (e env) status(t *testing.T, id int64) (Status, int) {
	t.Helper()
	m, err := e.svc.repo.FindByID(e.ctx, e.pool, id)
	if err != nil {
		t.Fatal(err)
	}
	return m.Status, m.Attempts
}

func TestEnqueue_같은_dedupeKey_는_한_번만_적힌다(t *testing.T) {
	e := setup(t)
	req := Request{TemplateCode: "DEADLINE", UserID: ptr[int64](1), DedupeKey: ptr("DEADLINE:1:10:3"),
		Variables: map[string]any{"dDayLabel": "D-3", "noticeTitle": "행복주택"}}
	first, err := e.svc.Enqueue(e.ctx, req)
	if err != nil || first.Duplicate {
		t.Fatalf("%v %+v", err, first)
	}
	second, _ := e.svc.Enqueue(e.ctx, req)
	if !second.Duplicate || second.ID != first.ID {
		t.Fatalf("중복이 새로 적힘: %+v", second)
	}
}

func TestEnqueue_없는_템플릿과_꺼진_템플릿은_거부(t *testing.T) {
	e := setup(t)
	_, err := e.svc.Enqueue(e.ctx, Request{TemplateCode: "NOPE", UserID: ptr[int64](1)})
	var he *httpx.Error
	if !errors.As(err, &he) || he.Code != httpx.TemplateNotFound {
		t.Fatalf("%v", err)
	}
	if _, err := e.pool.Exec(e.ctx, `update notification_templates set enabled=false where code='TEST'`); err != nil {
		t.Fatal(err)
	}
	if _, err := e.svc.Enqueue(e.ctx, Request{TemplateCode: "TEST", UserID: ptr[int64](1)}); !errors.As(err, &he) || he.Code != httpx.InvalidInput {
		t.Fatalf("%v", err)
	}
}

func TestDispatch_카테고리별_수신_규칙(t *testing.T) {
	e := setup(t)
	e.subscribe(t, 1, true, false)  // 정보성 켬, 광고 동의 안 함
	e.subscribe(t, 2, false, false) // 알림 끔
	info1, _ := e.svc.Enqueue(e.ctx, Request{TemplateCode: "DEADLINE", UserID: ptr[int64](1)})
	info2, _ := e.svc.Enqueue(e.ctx, Request{TemplateCode: "DEADLINE", UserID: ptr[int64](2)})
	ad1, _ := e.svc.Enqueue(e.ctx, Request{TemplateCode: "NEWSLETTER", UserID: ptr[int64](1),
		ScheduledFor: &db.LocalDateTime{Time: time.Date(2026, 1, 1, 12, 0, 0, 0, db.Seoul)}})
	verify, _ := e.svc.Enqueue(e.ctx, Request{TemplateCode: "EMAIL_VERIFY", Email: ptr("new@example.com"),
		Variables: map[string]any{"code": "123456", "expiresInMinutes": 10}})

	sent, err := e.svc.DispatchPending(e.ctx)
	if err != nil {
		t.Fatal(err)
	}
	if sent != 2 {
		t.Fatalf("보낸 건수 %d, want 2 (정보성 1 + 인증 메일 1)", sent)
	}
	for id, want := range map[int64]Status{info1.ID: Sent, info2.ID: Skipped, ad1.ID: Skipped, verify.ID: Sent} {
		if got, _ := e.status(t, id); got != want {
			t.Errorf("message %d: %s, want %s", id, got, want)
		}
	}
	if len(e.email.sent) != 1 || e.email.sent[0].Title != "[ZIPICK] 이메일 인증 코드: 123456" {
		t.Fatalf("email %+v", e.email.sent)
	}
}

func TestDispatch_실패하면_재시도하고_3번째에_FAILED(t *testing.T) {
	e := setup(t)
	e.subscribe(t, 1, true, false)
	e.slack.fail = errors.New("404 no_team")
	m, _ := e.svc.Enqueue(e.ctx, Request{TemplateCode: "DEADLINE", UserID: ptr[int64](1)})

	for i := 1; i <= 3; i++ {
		if _, err := e.svc.DispatchPending(e.ctx); err != nil {
			t.Fatal(err)
		}
		st, attempts := e.status(t, m.ID)
		want := Pending
		if i == 3 {
			want = Failed
		}
		if st != want || attempts != i {
			t.Fatalf("%d번째: %s attempts=%d", i, st, attempts)
		}
	}
}

func TestDispatch_예약_시각_전에는_보내지_않는다(t *testing.T) {
	e := setup(t)
	e.subscribe(t, 1, true, false)
	_, _ = e.svc.Enqueue(e.ctx, Request{TemplateCode: "DEADLINE", UserID: ptr[int64](1),
		ScheduledFor: &db.LocalDateTime{Time: db.Now().Add(time.Hour)}})
	if sent, _ := e.svc.DispatchPending(e.ctx); sent != 0 {
		t.Fatalf("예약 전 발송 %d", sent)
	}
}

func TestSendTest_설정이_없으면_거부_있으면_바로_보낸다(t *testing.T) {
	e := setup(t)
	var he *httpx.Error
	if _, err := e.svc.SendTest(e.ctx, 1); !errors.As(err, &he) || he.Code != httpx.SubscriptionNotFound {
		t.Fatalf("%v", err)
	}
	e.subscribe(t, 1, true, false)
	r, err := e.svc.SendTest(e.ctx, 1)
	if err != nil || r.Status != Sent || len(e.slack.sent) != 1 {
		t.Fatalf("%v %+v", err, r)
	}
	if h, _ := e.svc.History(e.ctx, 1); len(h) != 1 || h[0].TemplateCode != "TEST" {
		t.Fatalf("history %+v", h)
	}
}
