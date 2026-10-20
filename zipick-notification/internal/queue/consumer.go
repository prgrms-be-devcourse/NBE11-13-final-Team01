// Package queue 는 RabbitMQ 리스너다. 메인 서버가 "던져 놓은" 알림 요청을 꺼내 아웃박스에 적는다.
//
// HTTP(/internal/notifications)와 같은 요청 JSON 을 받는다. 차이는 전달 방식뿐이다:
//   - HTTP : 알림센터가 꺼져 있으면 요청이 실패하고 사라진다 (응답이 필요한 조회·설정·테스트 발송은 그대로 HTTP)
//   - 큐   : 알림센터가 꺼져 있어도 브로커에 남아 있다가, 켜지면 이어서 처리된다
//
// 큐 설정(알림센터와 메인 서버가 같은 인자로 선언해야 한다. 다르면 브로커가 거부한다):
//
//	notification.requests      durable, x-max-priority 9, 실패 시 notification.requests.dlq 로
//	notification.requests.dlq  durable. 형식이 잘못된 요청이 모인다. 운영자가 보고 지운다
//
// 처리 규칙 (at-least-once):
//   - 적는 데 성공 → ack
//   - 요청 형식 오류(템플릿 없음, 수신자 없음 등) → 실패 큐로 (다시 시도해도 같은 결과라서)
//   - DB 장애 같은 일시 오류 → 잠깐 쉬고 다시 큐에 (requeue)
//   - 같은 메시지가 두 번 올 수 있다. dedupeKey 가 있으면 Enqueue 가 걸러 준다
package queue

import (
	"context"
	"errors"
	"log/slog"
	"time"

	amqp "github.com/rabbitmq/amqp091-go"
	"github.com/zipick/notification/internal/httpx"
	"github.com/zipick/notification/internal/message"
)

const (
	QueueName = "notification.requests"
	DLQName   = "notification.requests.dlq"
	// MaxPriority 0~9. 메인 서버가 거래성(인증 메일) 9, 정보성 5 로 보낸다. 큰 값이 먼저 처리된다
	MaxPriority = 9
	prefetch    = 10
)

// Enqueuer 는 message.Service 가 만족하는 인터페이스. 테스트에서 가짜로 바꿀 수 있게 인터페이스로 받는다.
type Enqueuer interface {
	Enqueue(ctx context.Context, r message.Request) (message.Response, error)
}

// Declare 는 큐 두 개를 선언한다. 이미 같은 인자로 있으면 아무 일도 하지 않는다 (멱등).
func Declare(ch *amqp.Channel) error {
	if _, err := ch.QueueDeclare(DLQName, true, false, false, false, nil); err != nil {
		return err
	}
	_, err := ch.QueueDeclare(QueueName, true, false, false, false, amqp.Table{
		"x-max-priority":            int32(MaxPriority),
		"x-dead-letter-exchange":    "",
		"x-dead-letter-routing-key": DLQName,
	})
	return err
}

// Run 은 ctx 가 취소될 때까지 리슨한다. 연결이 끊기면 5초마다 다시 붙는다.
// main 에서 `go queue.Run(...)` 으로 별도 고루틴에서 돌린다.
func Run(ctx context.Context, url string, svc Enqueuer) {
	for {
		err := consumeUntilClosed(ctx, url, svc)
		if ctx.Err() != nil {
			return
		}
		slog.Warn("RabbitMQ 연결 끊김, 5초 뒤 재시도", "err", err)
		select {
		case <-time.After(5 * time.Second):
		case <-ctx.Done():
			return
		}
	}
}

// consumeUntilClosed 는 연결 하나의 수명 동안 메시지를 처리한다. 연결이 닫히면 error 로 돌아온다.
func consumeUntilClosed(ctx context.Context, url string, svc Enqueuer) error {
	conn, err := amqp.Dial(url)
	if err != nil {
		return err
	}
	defer conn.Close()
	ch, err := conn.Channel()
	if err != nil {
		return err
	}
	defer ch.Close()
	if err := Declare(ch); err != nil {
		return err
	}
	// prefetch: 확인(ack) 안 한 메시지를 한 번에 10개까지만 받는다. 처리 중 죽으면 그 10개는 브로커가 다시 나눠 준다
	if err := ch.Qos(prefetch, 0, false); err != nil {
		return err
	}
	deliveries, err := ch.Consume(QueueName, "zipick-notification", false, false, false, false, nil)
	if err != nil {
		return err
	}
	slog.Info("RabbitMQ 리슨 시작", "queue", QueueName)
	closed := conn.NotifyClose(make(chan *amqp.Error, 1))
	for {
		select {
		case <-ctx.Done():
			return nil
		case e := <-closed:
			if e == nil {
				return errors.New("connection closed")
			}
			return e
		case d, ok := <-deliveries:
			if !ok {
				return errors.New("delivery channel closed")
			}
			handle(ctx, svc, d)
		}
	}
}

// handle 은 메시지 하나를 처리하고 ack / 실패 큐 / 재시도 중 하나로 끝낸다.
func handle(ctx context.Context, svc Enqueuer, d amqp.Delivery) {
	var req message.Request
	if err := unmarshal(d.Body, &req); err != nil {
		slog.Warn("큐 메시지 JSON 오류 → 실패 큐", "err", err)
		_ = d.Nack(false, false) // requeue=false → DLQ
		return
	}
	res, err := svc.Enqueue(ctx, req)
	if err == nil {
		slog.Info("큐 요청 적음", "template", req.TemplateCode, "message", res.ID, "duplicate", res.Duplicate, "redelivered", d.Redelivered)
		_ = d.Ack(false)
		return
	}
	var bad *httpx.Error
	if errors.As(err, &bad) {
		// 요청 자체가 잘못됐다 (없는 템플릿, 수신자 없음 …). 다시 시도해도 같으니 실패 큐로 보내 운영자가 보게 한다
		slog.Warn("큐 요청 거부 → 실패 큐", "template", req.TemplateCode, "code", bad.Code, "msg", bad.Message)
		_ = d.Nack(false, false)
		return
	}
	// DB 장애 등 일시 오류: 잠깐 쉬고 큐에 되돌린다 (바로 되돌리면 같은 메시지로 바쁘게 돈다)
	slog.Error("큐 요청 처리 실패, 재시도", "template", req.TemplateCode, "err", err)
	time.Sleep(time.Second)
	_ = d.Nack(false, true)
}
