// Package scheduler 는 DB 폴링 스케줄러다 (기본 1분).
// ① 시각이 된 캠페인을 풀어 적고 ② 때가 된 PENDING 메시지를 보낸다.
// dedupe_key 와 SELECT ... FOR UPDATE SKIP LOCKED 덕분에 주기가 겹치거나 서버가 여러 대여도 중복이 없다.
package scheduler

import (
	"context"
	"log/slog"
	"time"
)

// 스케줄러가 매 주기 부르는 두 단계. 인터페이스로 받아 campaign/message 패키지를 직접 알지 않는다.

// Queuer 는 시각이 된 캠페인을 아웃박스에 적는다 (campaign.Service).
type Queuer interface {
	QueueDue(ctx context.Context) (int, error)
}

// Dispatcher 는 때가 된 메시지를 보낸다 (message.Service).
type Dispatcher interface {
	DispatchPending(ctx context.Context) (int, error)
}

// Run 은 ctx 가 취소될 때까지 돈다. main 에서 `go scheduler.Run(...)` 으로 별도 고루틴에서 실행한다.
// 고루틴은 Java 의 스레드보다 훨씬 가벼운 실행 단위다 (수 KB).
func Run(ctx context.Context, every time.Duration, initialDelay time.Duration, campaigns Queuer, messages Dispatcher) {
	select {
	case <-time.After(initialDelay):
	case <-ctx.Done():
		return
	}
	ticker := time.NewTicker(every)
	defer ticker.Stop()
	for {
		runOnce(ctx, campaigns, messages)
		select {
		case <-ticker.C:
		case <-ctx.Done():
			return
		}
	}
}

func runOnce(ctx context.Context, campaigns Queuer, messages Dispatcher) {
	// 한 주기가 너무 오래 걸리지 않게 상한을 둔다 (외부 API 가 멈춰도 다음 주기는 돈다)
	ctx, cancel := context.WithTimeout(ctx, 5*time.Minute)
	defer cancel()
	queued, err := campaigns.QueueDue(ctx)
	if err != nil {
		slog.Error("캠페인 적재 실패", "err", err)
	}
	sent, err := messages.DispatchPending(ctx)
	if err != nil {
		slog.Error("발송 실패", "err", err)
	}
	if queued+sent > 0 {
		slog.Info("알림 스케줄러", "queued", queued, "sent", sent)
	}
}
