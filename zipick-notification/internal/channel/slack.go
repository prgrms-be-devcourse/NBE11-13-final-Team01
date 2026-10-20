package channel

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"
)

// SlackWebhookPrefix hooks.slack.com 주소로만 보낸다 (임의 주소로 요청을 보내는 SSRF 방지).
const SlackWebhookPrefix = "https://hooks.slack.com/"

// Slack 은 Incoming Webhook 채널. 사용자가 설정에 넣은 URL 로 {"text": ...} 를 POST 한다.
type Slack struct{ client *http.Client }

func NewSlack() *Slack { return &Slack{client: &http.Client{Timeout: 5 * time.Second}} }

func (s *Slack) Name() string { return "slack" }

func (s *Slack) Supports(r Recipient) bool {
	return r.SlackWebhookURL != nil && strings.HasPrefix(*r.SlackWebhookURL, SlackWebhookPrefix)
}

func (s *Slack) Send(ctx context.Context, r Recipient, m Message) error {
	text := "*" + m.Title + "*\n" + m.Body
	if m.URL != nil {
		text += "\n" + *m.URL
	}
	payload, _ := json.Marshal(map[string]string{"text": text})
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, *r.SlackWebhookURL, bytes.NewReader(payload))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")
	res, err := s.client.Do(req)
	if err != nil {
		return err
	}
	defer res.Body.Close()
	if res.StatusCode/100 != 2 {
		body, _ := io.ReadAll(io.LimitReader(res.Body, 200))
		// 예: 404 Not Found: "no_team" — Spring 버전과 같은 형식으로 남긴다
		return fmt.Errorf("%s: %q", res.Status, strings.TrimSpace(string(body)))
	}
	return nil
}
