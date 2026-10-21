package subscription

import (
	"strings"
	"testing"
	"time"
)

func sp(s string) *string { return &s }

func TestToResponse_웹훅은_끝_6자만_보여준다(t *testing.T) {
	at := time.Date(2026, 10, 20, 9, 57, 29, 0, time.UTC)
	r := ToResponse(Subscription{UserID: 7, Enabled: true, SlackWebhookURL: sp("https://hooks.slack.com/services/T/B/abcdef123456"),
		MarketingConsent: true, MarketingConsentedAt: &at, DeadlineDays: "3,1,0"}, true)
	if *r.SlackWebhookMasked != "…123456" || !r.SlackConfigured || !r.EmailAvailable || r.MarketingConsentedAt.Hour() != 9 {
		t.Fatalf("%+v", r)
	}
	if strings.Contains(*r.SlackWebhookMasked, "hooks.slack.com") {
		t.Fatal("주소 전체가 노출됨")
	}
	if ToResponse(Subscription{DeadlineDays: "3"}, false).SlackWebhookMasked != nil {
		t.Fatal("웹훅이 없으면 nil")
	}
}

func TestEmptyResponse_설정이_없는_사용자의_기본값(t *testing.T) {
	r := EmptyResponse(9, false)
	if r.UserID != 9 || r.Enabled || !r.NewMatchEnabled || len(r.DeadlineDays) != 3 {
		t.Fatalf("%+v", r)
	}
}

func TestValidate_이메일과_웹훅_길이(t *testing.T) {
	if (Request{Email: sp("not-an-email")}).Validate() == nil {
		t.Fatal("잘못된 이메일 통과")
	}
	if (Request{Email: sp("")}).Validate() != nil || (Request{SlackWebhookURL: sp("")}).Validate() != nil {
		t.Fatal("빈 값(지우기)은 통과해야 함")
	}
	long := "https://hooks.slack.com/" + strings.Repeat("x", 500)
	if (Request{SlackWebhookURL: &long}).Validate() == nil {
		t.Fatal("500자 넘는 웹훅 통과")
	}
}

func TestBlankToNil(t *testing.T) {
	if blankToNil("  ") != nil || *blankToNil(" a@b.c ") != "a@b.c" {
		t.Fatal("blankToNil")
	}
	if (Subscription{DeadlineDays: ""}).DeadlineDayList() == nil {
		t.Fatal("빈 목록은 nil 이 아니라 [] (JSON 에 null 이 나가지 않게)")
	}
}
