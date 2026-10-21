package channel

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/zipick/notification/internal/config"
)

func ptr(s string) *string { return &s }

func TestRecipient와_Message(t *testing.T) {
	if !(Recipient{}).IsEmpty() || (Recipient{Email: ptr("a@b.c")}).IsEmpty() {
		t.Fatal("IsEmpty")
	}
	if (Message{Body: "본문"}).Text() != "본문" || (Message{Body: "본문", URL: ptr("https://x")}).Text() != "본문\n\nhttps://x" {
		t.Fatal("Text")
	}
}

func TestSlack_hooks_slack_com_주소만_보낸다(t *testing.T) {
	s := NewSlack()
	if s.Name() != "slack" {
		t.Fatal(s.Name())
	}
	if !s.Supports(Recipient{SlackWebhookURL: ptr("https://hooks.slack.com/services/T/B/x")}) {
		t.Fatal("슬랙 주소를 거부")
	}
	for _, bad := range []string{"https://evil.example/hooks.slack.com/", "http://hooks.slack.com/x"} {
		if s.Supports(Recipient{SlackWebhookURL: ptr(bad)}) {
			t.Fatalf("허용하면 안 되는 주소: %s", bad)
		}
	}
	if s.Supports(Recipient{Email: ptr("a@b.c")}) {
		t.Fatal("웹훅 없이 지원한다고 함")
	}
}

func TestSlack_제목_본문_링크를_text_하나로_보낸다(t *testing.T) {
	var got map[string]string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		b, _ := io.ReadAll(r.Body)
		_ = json.Unmarshal(b, &got)
		if r.Header.Get("Content-Type") != "application/json" {
			t.Errorf("content-type %s", r.Header.Get("Content-Type"))
		}
		w.WriteHeader(200)
	}))
	defer srv.Close()

	err := NewSlack().Send(context.Background(), Recipient{SlackWebhookURL: ptr(srv.URL)},
		Message{Title: "접수 마감 D-3", Body: "본문", URL: ptr("https://lh.or.kr/1")})
	if err != nil {
		t.Fatal(err)
	}
	if got["text"] != "*접수 마감 D-3*\n본문\nhttps://lh.or.kr/1" {
		t.Fatalf("text = %q", got["text"])
	}
}

func TestSlack_2xx_가_아니면_상태와_본문을_오류로(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(404)
		_, _ = w.Write([]byte("no_team"))
	}))
	defer srv.Close()
	err := NewSlack().Send(context.Background(), Recipient{SlackWebhookURL: ptr(srv.URL)}, Message{Title: "t", Body: "b"})
	if err == nil || !strings.Contains(err.Error(), "404") || !strings.Contains(err.Error(), "no_team") {
		t.Fatalf("got %v", err)
	}
}

func TestBuildMail_한글_제목과_본문을_base64로(t *testing.T) {
	raw := string(buildMail("no-reply@zipick.kr", "ZIPICK", "u@example.com", "이메일 인증 코드", "코드는 123456"))
	subject := "=?UTF-8?B?" + base64.StdEncoding.EncodeToString([]byte("이메일 인증 코드")) + "?="
	for _, want := range []string{"To: u@example.com\r\n", "Subject: " + subject, "<no-reply@zipick.kr>", "charset=UTF-8",
		base64.StdEncoding.EncodeToString([]byte("코드는 123456"))} {
		if !strings.Contains(raw, want) {
			t.Fatalf("메일 원문에 %q 없음:\n%s", want, raw)
		}
	}
	if strings.Contains(string(buildMail("a@b.c", "", "u@x", "s", "t")), "=?UTF-8?B?") && !strings.Contains(string(buildMail("a@b.c", "", "u@x", "s", "t")), "From: a@b.c") {
		t.Fatal("이름이 없으면 주소만 써야 함")
	}
}

func TestBuild_이메일_provider_에_따라_채널이_정해진다(t *testing.T) {
	none, _ := Build(context.Background(), config.EmailConfig{Provider: "none"})
	if len(none) != 1 || HasEmail(none) {
		t.Fatalf("none: %d", len(none))
	}
	smtp, _ := Build(context.Background(), config.EmailConfig{Provider: "smtp", SMTPHost: "smtp.example.com"})
	if !HasEmail(smtp) || smtp[1].Name() != "email" || !smtp[1].Supports(Recipient{Email: ptr("a@b.c")}) {
		t.Fatal("smtp 채널이 없음")
	}
	if _, err := Build(context.Background(), config.EmailConfig{Provider: "ses"}); err == nil {
		t.Fatal("SES 는 발신 주소가 필요")
	}
}
