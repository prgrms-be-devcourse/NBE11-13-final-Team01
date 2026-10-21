package queue

import (
	"context"
	"errors"
	"testing"

	amqp "github.com/rabbitmq/amqp091-go"
	"github.com/zipick/notification/internal/httpx"
	"github.com/zipick/notification/internal/message"
)

// fakeAck 는 브로커 대신 ack/nack 호출을 기록한다 (amqp.Delivery 가 이 인터페이스로 응답한다)
type fakeAck struct {
	acked, nacked, requeued bool
}

func (f *fakeAck) Ack(uint64, bool) error { f.acked = true; return nil }
func (f *fakeAck) Nack(_ uint64, _ bool, requeue bool) error {
	f.nacked, f.requeued = true, requeue
	return nil
}
func (f *fakeAck) Reject(_ uint64, requeue bool) error { return f.Nack(0, false, requeue) }

type stubEnqueuer struct{ err error }

func (s stubEnqueuer) Enqueue(context.Context, message.Request) (message.Response, error) {
	return message.Response{ID: 1}, s.err
}

func deliver(t *testing.T, body string, err error) *fakeAck {
	t.Helper()
	ack := &fakeAck{}
	handle(context.Background(), stubEnqueuer{err: err}, amqp.Delivery{Acknowledger: ack, Body: []byte(body)})
	return ack
}

const okBody = `{"templateCode":"DEADLINE","userId":1,"dedupeKey":"DEADLINE:1:10:3"}`

func TestHandle_적으면_ack(t *testing.T) {
	if a := deliver(t, okBody, nil); !a.acked || a.nacked {
		t.Fatalf("%+v", a)
	}
}

func TestHandle_JSON_이_깨졌으면_실패_큐로(t *testing.T) {
	if a := deliver(t, "{broken", nil); !a.nacked || a.requeued {
		t.Fatalf("%+v", a)
	}
}

func TestHandle_요청이_잘못됐으면_다시_시도하지_않고_실패_큐로(t *testing.T) {
	if a := deliver(t, okBody, httpx.Fail(httpx.TemplateNotFound, "없는 템플릿")); !a.nacked || a.requeued {
		t.Fatalf("%+v", a)
	}
}

func TestHandle_DB_장애_같은_일시_오류는_큐에_되돌린다(t *testing.T) {
	if a := deliver(t, okBody, errors.New("db down")); !a.nacked || !a.requeued {
		t.Fatalf("%+v", a)
	}
}

func TestRun_취소되면_바로_끝난다(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	done := make(chan struct{})
	go func() { Run(ctx, "amqp://nobody:nothing@127.0.0.1:1/", stubEnqueuer{}); close(done) }()
	<-done
}
