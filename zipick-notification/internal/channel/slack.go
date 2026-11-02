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

// Slack 은 Incoming Webhook 채널. 요청에 적힌 URL 로 {"text": ...} 를 POST 한다.
type Slack struct{ client *http.Client }

// 리다이렉트를 따라가지 않는다: 없는 웹훅 주소는 Slack 이 302 로 안내 페이지로 보내는데, 따라가면 200 이 되어 성공으로 오인한다.
func NewSlack() *Slack {
	return &Slack{client: &http.Client{Timeout: 5 * time.Second,
		CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}}
}

func (s *Slack) Name() string { return "slack" }

func (s *Slack) HasAddress(r Recipient) bool {
	return r.SlackWebhookURL != nil && strings.HasPrefix(*r.SlackWebhookURL, SlackWebhookPrefix)
}

func (s *Slack) Send(ctx context.Context, r Recipient, c Content) error {
	payload, _ := json.Marshal(map[string]string{"text": c.Body})
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
		return fmt.Errorf("%s: %q", res.Status, strings.TrimSpace(string(body)))
	}
	return nil
}
