package queue

import (
	"context"
	"errors"
	"testing"

	amqp "github.com/rabbitmq/amqp091-go"
	"github.com/zipick/notification/internal/httpx"
	"github.com/zipick/notification/internal/message"
)

type fakeEnqueuer struct {
	err error
	got message.Request
}

func (f *fakeEnqueuer) Enqueue(_ context.Context, r message.Request) (message.Response, error) {
	f.got = r
	return message.Response{ID: 1}, f.err
}

// amqp.Delivery 의 Ack/Nack 은 Acknowledger 인터페이스로 간다. 가짜로 바꿔 어떤 결정을 했는지 본다.
type fakeAck struct{ result string }

func (f *fakeAck) Ack(uint64, bool) error { f.result = "ack"; return nil }
func (f *fakeAck) Nack(_ uint64, _ bool, requeue bool) error {
	if requeue {
		f.result = "requeue"
	} else {
		f.result = "dlq"
	}
	return nil
}
func (f *fakeAck) Reject(uint64, bool) error { f.result = "reject"; return nil }

func TestHandle(t *testing.T) {
	body := []byte(`{"source":"main","templateCode":"MAIN_TEST","channels":["email"],"to":{"email":"a@b.co"},"variables":{"deadlineDays":3},"tags":{"user":"2"}}`)
	cases := []struct {
		name string
		body []byte
		err  error
		want string
	}{
		{"정상", body, nil, "ack"},
		{"JSON 깨짐 → 실패 큐", []byte(`{`), nil, "dlq"},
		{"요청 오류 → 실패 큐", body, httpx.Fail(httpx.TemplateNotFound, "x"), "dlq"},
		{"일시 오류 → 되돌림", body, errors.New("db down"), "requeue"},
	}
	for _, c := range cases {
		ack := &fakeAck{}
		svc := &fakeEnqueuer{err: c.err}
		handle(context.Background(), svc, delivery(c.body, ack))
		if ack.result != c.want {
			t.Errorf("%s: %s, want %s", c.name, ack.result, c.want)
		}
		if c.name == "정상" && (svc.got.Source != "main" || svc.got.Tags["user"] != "2" || svc.got.Variables["deadlineDays"] != float64(3)) {
			t.Errorf("요청 해석: %+v", svc.got)
		}
	}
}

func TestQueueNamesAreV1(t *testing.T) {
	if QueueName != "notification.requests.v1" || DLQName != "notification.requests.v1.dlq" {
		t.Fatal("메인 서버의 NotificationQueueConfig 와 같아야 한다")
	}
}

func delivery(body []byte, ack amqp.Acknowledger) amqp.Delivery {
	return amqp.Delivery{Acknowledger: ack, Body: body}
}
