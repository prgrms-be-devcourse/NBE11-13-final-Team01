package message

import (
	"context"
	"errors"
	"strings"
	"testing"

	"github.com/zipick/notification/internal/channel"
	"github.com/zipick/notification/internal/config"
	"github.com/zipick/notification/internal/httpx"
	"github.com/zipick/notification/internal/template"
)

// fakeChannel 은 보내는 대신 기록만 한다. fail 이 true 면 실패한다.
type fakeChannel struct {
	name string
	fail bool
	sent []channel.Content
}

func (f *fakeChannel) Name() string { return f.name }
func (f *fakeChannel) HasAddress(r channel.Recipient) bool {
	if f.name == "email" {
		return r.Email != nil
	}
	return r.SlackWebhookURL != nil
}
func (f *fakeChannel) Send(_ context.Context, _ channel.Recipient, c channel.Content) error {
	if f.fail {
		return errors.New("boom")
	}
	f.sent = append(f.sent, c)
	return nil
}

func testTemplates() *template.Store {
	return template.FromList(
		template.Template{Code: "MAIN_TEST", Email: &template.Email{Subject: "[ZIPICK] 테스트 {{name}}", Body: "안녕 {{name}}"}, Slack: &template.Slack{Text: "테스트 {{name}}"}},
		template.Template{Code: "AUTH_EMAIL_VERIFY", Email: &template.Email{Subject: "코드 {{code}}", Body: "{{code}}"}},
	)
}

func newTestService(chs ...channel.Channel) *Service {
	return NewService(NewRepo(nil), testTemplates(), chs, config.Config{MaxAttempts: 3, RetentionDays: 90})
}

func str(s string) *string { return &s }

func codeOf(t *testing.T, err error) httpx.Code {
	t.Helper()
	var e *httpx.Error
	if !errors.As(err, &e) {
		t.Fatalf("httpx.Error 가 아님: %v", err)
	}
	return e.Code
}

func TestValidate(t *testing.T) {
	svc := newTestService(&fakeChannel{name: "email"}, &fakeChannel{name: "slack"})
	ok := Request{Source: "main", TemplateCode: "main_test", Channels: []string{"email"}, To: Recipient{Email: str("a@b.co")}}
	if _, err := svc.Validate(ok); err != nil {
		t.Fatalf("정상 요청이 거부됨: %v", err)
	}
	cases := map[string]struct {
		mutate func(r *Request)
		code   httpx.Code
	}{
		"source 대문자":     {func(r *Request) { r.Source = "Main" }, httpx.InvalidInput},
		"채널 없음":          {func(r *Request) { r.Channels = nil }, httpx.InvalidInput},
		"모르는 템플릿":        {func(r *Request) { r.TemplateCode = "NOPE" }, httpx.TemplateNotFound},
		"모르는 채널":         {func(r *Request) { r.Channels = []string{"sms"} }, httpx.InvalidInput},
		"채널 중복":          {func(r *Request) { r.Channels = []string{"email", "email"} }, httpx.InvalidInput},
		"이메일 골랐는데 주소 없음": {func(r *Request) { r.To = Recipient{} }, httpx.NoRecipient},
		"슬랙 골랐는데 주소 없음":  {func(r *Request) { r.Channels = []string{"slack"} }, httpx.NoRecipient},
		"템플릿에 슬랙 문구 없음": {func(r *Request) {
			r.TemplateCode = "AUTH_EMAIL_VERIFY"
			r.Channels = []string{"slack"}
			r.To.SlackWebhookURL = str("https://hooks.slack.com/x")
		}, httpx.InvalidInput},
		"이메일 형식":            {func(r *Request) { r.To.Email = str("not-an-email") }, httpx.InvalidInput},
		"슬랙 주소가 hooks 가 아님": {func(r *Request) { r.Channels = []string{"slack"}; r.To.SlackWebhookURL = str("https://evil.example/x") }, httpx.InvalidInput},
		"delivery 값":        {func(r *Request) { r.Delivery = "later" }, httpx.InvalidInput},
		"dedupeKey 빈 문자열":   {func(r *Request) { r.DedupeKey = str("") }, httpx.InvalidInput},
	}
	for name, c := range cases {
		r := ok
		r.To = Recipient{Email: str("a@b.co")}
		c.mutate(&r)
		_, err := svc.Validate(r)
		if err == nil {
			t.Errorf("%s: 거부돼야 함", name)
			continue
		}
		if got := codeOf(t, err); got != c.code {
			t.Errorf("%s: code=%s, want %s", name, got, c.code)
		}
	}
}

func TestValidate_ChannelUnavailable(t *testing.T) {
	svc := newTestService(&fakeChannel{name: "slack"}) // 이메일 꺼짐
	_, err := svc.Validate(Request{Source: "main", TemplateCode: "MAIN_TEST", Channels: []string{"email"}, To: Recipient{Email: str("a@b.co")}})
	if codeOf(t, err) != httpx.ChannelUnavailable {
		t.Fatalf("got %v", err)
	}
	if av := svc.Available(); av["email"] || !av["slack"] {
		t.Fatalf("Available=%v", av)
	}
}

func TestBuild_RendersPerChannelAndRecordsMissing(t *testing.T) {
	tpl, _ := testTemplates().Get("MAIN_TEST")
	m := Build(tpl, Request{Source: "main", TemplateCode: "MAIN_TEST", Channels: []string{"email", "slack"},
		To: Recipient{Email: str("a@b.co"), SlackWebhookURL: str("https://hooks.slack.com/x")}, Tags: map[string]string{"user": "2"}}, nil)
	if *m.EmailSubject != "[ZIPICK] 테스트 " || *m.EmailBody != "안녕 " || *m.SlackText != "테스트 " {
		t.Fatalf("빈 변수는 빈칸으로: %q %q %q", *m.EmailSubject, *m.EmailBody, *m.SlackText)
	}
	if len(m.MissingVariables) != 1 || m.MissingVariables[0] != "name" {
		t.Fatalf("missing=%v", m.MissingVariables)
	}
	if m.Variables != "{}" || m.Tags["user"] != "2" || m.ScheduledFor.IsZero() {
		t.Fatalf("variables=%s tags=%v scheduled=%v", m.Variables, m.Tags, m.ScheduledFor)
	}
	only := Build(tpl, Request{Channels: []string{"slack"}, Variables: map[string]any{"name": "지우"}}, nil)
	if only.EmailSubject != nil || *only.SlackText != "테스트 지우" {
		t.Fatalf("고르지 않은 채널 문구는 비어야 함: %+v", only)
	}
}

func TestSummarize(t *testing.T) {
	cases := []struct {
		ds   []DeliveryStatus
		want Status
	}{
		{[]DeliveryStatus{DSent, DSent}, Sent},
		{[]DeliveryStatus{DSent, DFailed}, Partial},
		{[]DeliveryStatus{DFailed, DFailed}, Failed},
		{[]DeliveryStatus{DSent, DPending}, Pending},
	}
	for _, c := range cases {
		var ds []Delivery
		for _, s := range c.ds {
			ds = append(ds, Delivery{Status: s})
		}
		if got := Summarize(ds); got != c.want {
			t.Errorf("%v → %s, want %s", c.ds, got, c.want)
		}
	}
}

func TestToResponse_TitleFallsBackToSlackFirstLine(t *testing.T) {
	m := Message{SlackText: str("첫 줄\n둘째 줄"), Channels: []string{"slack"}}
	if r := ToResponse(m, nil, false); r.Title != "첫 줄" || len(r.Deliveries) != 0 || len(r.Tags) != 0 {
		t.Fatalf("%+v", r)
	}
	if !strings.HasPrefix(cutRunes("가나다라", 2), "가나") || cutRunes("가나다라", 2) != "가나" {
		t.Fatal("cutRunes 는 글자 단위로 잘라야 함")
	}
}
