package subscription

import (
	"context"
	"testing"

	"github.com/zipick/notification/internal/testdb"
)

func TestSave_null_은_유지_빈값은_삭제_동의_시각은_바뀔_때만(t *testing.T) {
	repo := NewRepo(testdb.Pool(t))
	ctx := context.Background()
	slack, email, yes := "https://hooks.slack.com/services/T/B/x", "a@b.c", true

	first, err := repo.Save(ctx, 1, Request{Enabled: true, SlackWebhookURL: &slack, Email: &email, MarketingConsent: &yes})
	if err != nil || !first.HasSlack() || !first.HasEmail() || first.MarketingConsentedAt == nil || first.DeadlineDays != DefaultDeadlineDays {
		t.Fatalf("%v %+v", err, first)
	}
	consentedAt := *first.MarketingConsentedAt

	kept, _ := repo.Save(ctx, 1, Request{Enabled: true, MarketingConsent: &yes}) // 주소를 안 보냄 → 유지
	if !kept.HasSlack() || !kept.HasEmail() || !kept.MarketingConsentedAt.Equal(consentedAt) {
		t.Fatalf("%+v", kept)
	}

	empty, days, no := "", "7, 1", false
	cleared, _ := repo.Save(ctx, 1, Request{Enabled: false, Email: &empty, MarketingConsent: &no, DeadlineDays: &days})
	if cleared.HasEmail() || !cleared.HasSlack() || cleared.MarketingConsent || cleared.MarketingConsentedAt != nil || cleared.DeadlineDays != "7,1" {
		t.Fatalf("%+v", cleared)
	}

	if s, _ := repo.FindByUser(ctx, 99); s != nil {
		t.Fatal("없는 사용자")
	}
	enabled, _ := repo.FindEnabled(ctx)
	byIDs, _ := repo.FindByUserIDs(ctx, []int64{1, 2})
	if len(enabled) != 0 || len(byIDs) != 1 {
		t.Fatalf("enabled=%d byIDs=%d", len(enabled), len(byIDs))
	}
}
