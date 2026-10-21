package campaign

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/zipick/notification/internal/config"
	"github.com/zipick/notification/internal/db"
	"github.com/zipick/notification/internal/httpx"
	"github.com/zipick/notification/internal/message"
	"github.com/zipick/notification/internal/subscription"
	"github.com/zipick/notification/internal/template"
	"github.com/zipick/notification/internal/testdb"
)

func TestCampaign_동의자에게만_적고_취소하면_안_나간_것도_취소(t *testing.T) {
	pool := testdb.Pool(t)
	ctx := context.Background()
	templates := template.NewRepo(pool)
	if err := template.Seed(ctx, templates); err != nil {
		t.Fatal(err)
	}
	subs := subscription.NewRepo(pool)
	yes, no := true, false
	_, _ = subs.Save(ctx, 1, subscription.Request{Enabled: true, MarketingConsent: &yes})
	_, _ = subs.Save(ctx, 2, subscription.Request{Enabled: true, MarketingConsent: &no})
	_, _ = subs.Save(ctx, 3, subscription.Request{Enabled: true, MarketingConsent: &yes})
	msgRepo := message.NewRepo(pool)
	msgSvc, _ := message.NewService(msgRepo, subs, templates, nil, config.Config{QuietStart: "21:00", QuietEnd: "08:00", MaxAttempts: 3})
	svc := NewService(pool, msgRepo, msgSvc, subs, templates)

	// 광고 템플릿을 정보성 대상에 보내면 거부
	var he *httpx.Error
	if _, err := svc.Create(ctx, Request{Name: "잘못", TemplateCode: "NEWSLETTER", Audience: Enabled}); !errors.As(err, &he) {
		t.Fatalf("%v", err)
	}
	c, err := svc.Create(ctx, Request{Name: "10월 소식", TemplateCode: "NEWSLETTER", Audience: MarketingConsented,
		Variables:   map[string]any{"headline": "새 공고", "message": "본문"},
		ScheduledAt: &db.LocalDateTime{Time: time.Date(2026, 1, 1, 12, 0, 0, 0, db.Seoul)}})
	if err != nil || c.Status != Scheduled {
		t.Fatalf("%v %+v", err, c)
	}

	n, err := svc.QueueDue(ctx)
	if err != nil || n != 2 {
		t.Fatalf("적은 건수 %d %v (동의자 2명)", n, err)
	}
	if again, _ := svc.QueueDue(ctx); again != 0 {
		t.Fatal("이미 적은 캠페인을 다시 적음")
	}
	got, _ := svc.Get(ctx, c.ID)
	if got.Status != Queued || got.QueuedCount != 2 || got.PendingCount != 2 {
		t.Fatalf("%+v", got)
	}

	cancelled, _ := svc.Cancel(ctx, c.ID)
	if cancelled.Status != Cancelled || cancelled.PendingCount != 0 {
		t.Fatalf("%+v", cancelled)
	}
	list, _ := svc.List(ctx)
	if len(list) != 1 {
		t.Fatalf("list %d", len(list))
	}
	var nf *httpx.Error
	if _, err := svc.Get(ctx, 999); !errors.As(err, &nf) || nf.Code != httpx.CampaignNotFound {
		t.Fatalf("%v", err)
	}
}
