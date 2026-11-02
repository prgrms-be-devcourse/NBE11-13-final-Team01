package channel

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func str(s string) *string { return &s }

func TestSlack_HasAddressOnlyForHooksURL(t *testing.T) {
	s := NewSlack()
	if s.HasAddress(Recipient{SlackWebhookURL: str("https://evil.example/x")}) || !s.HasAddress(Recipient{SlackWebhookURL: str("https://hooks.slack.com/services/x")}) {
		t.Fatal("hooks.slack.com 만 허용")
	}
}

func TestSlack_SendPostsText(t *testing.T) {
	var got map[string]string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		b, _ := io.ReadAll(r.Body)
		_ = json.Unmarshal(b, &got)
		if strings.Contains(got["text"], "fail") {
			w.WriteHeader(500)
		}
		if strings.Contains(got["text"], "redirect") {
			http.Redirect(w, r, "/elsewhere", http.StatusFound)
		}
	}))
	defer srv.Close()
	s := NewSlack()
	url := srv.URL + "/hook"
	if err := s.Send(context.Background(), Recipient{SlackWebhookURL: &url}, Content{Body: "안녕"}); err != nil || got["text"] != "안녕" {
		t.Fatalf("%v %v", err, got)
	}
	if err := s.Send(context.Background(), Recipient{SlackWebhookURL: &url}, Content{Body: "fail"}); err == nil {
		t.Fatal("2xx 가 아니면 실패")
	}
	if err := s.Send(context.Background(), Recipient{SlackWebhookURL: &url}, Content{Body: "redirect"}); err == nil {
		t.Fatal("302(없는 웹훅)는 따라가지 않고 실패")
	}
}

func TestBuildMail_EncodesKoreanSubject(t *testing.T) {
	msg := string(buildMail("no-reply@zipick.test", "ZIPICK", "a@b.co", "제목", "본문"))
	if !strings.Contains(msg, "Subject: =?UTF-8?B?") || !strings.Contains(msg, "To: a@b.co") || !strings.Contains(msg, "<no-reply@zipick.test>") {
		t.Fatal(msg)
	}
}

func TestAvailable(t *testing.T) {
	av := Available([]Channel{NewSlack()})
	if !av["slack"] || av["email"] {
		t.Fatalf("%v", av)
	}
}
