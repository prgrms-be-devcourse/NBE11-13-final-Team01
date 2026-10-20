package queue

import (
	"context"
	"testing"

	"github.com/zipick/notification/internal/message"
)

// 가짜 Enqueuer: 받은 요청을 기록만 한다
type fake struct{ got []message.Request }

func (f *fake) Enqueue(_ context.Context, r message.Request) (message.Response, error) {
	f.got = append(f.got, r)
	return message.Response{ID: int64(len(f.got))}, nil
}

// 메인 서버가 보내는 JSON(LocalDateTime 형식 포함)이 그대로 Request 로 읽히는지
func TestRequestJSONFromMainServer(t *testing.T) {
	body := []byte(`{"templateCode":"DEADLINE","userId":2,"email":null,"slackWebhookUrl":null,"variables":{"dDayLabel":"D-3","score":87.5},"dedupeKey":"DEADLINE:2:25:3","scheduledFor":"2026-10-21T08:00:00"}`)
	var req message.Request
	if err := unmarshal(body, &req); err != nil {
		t.Fatal(err)
	}
	if req.TemplateCode != "DEADLINE" || *req.UserID != 2 || *req.DedupeKey != "DEADLINE:2:25:3" || req.ScheduledFor.Hour() != 8 {
		t.Fatalf("got %+v", req)
	}
	if req.Variables["score"] != 87.5 {
		t.Fatalf("variables lost: %v", req.Variables)
	}
}
