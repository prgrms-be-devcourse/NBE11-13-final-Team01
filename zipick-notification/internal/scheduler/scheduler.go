// Package scheduler 는 DB 폴링 스케줄러다 (기본 1분).
// 때가 된 PENDING 메시지를 보내고, 하루에 한 번 오래된 기록의 주소를 가린다.
// dedupe_key 와 SELECT ... FOR UPDATE SKIP LOCKED 덕분에 주기가 겹치거나 서버가 여러 대여도 중복이 없다.
package scheduler

import (
	"context"
	"log/slog"
	"time"
)

// Dispatcher 는 때가 된 메시지를 보내고(매 주기), 오래된 주소를 가린다(하루 한 번). message.Service 가 만족한다.
// 인터페이스로 받아 message 패키지를 직접 알지 않는다.
type Dispatcher interface {
	DispatchPending(ctx context.Context) (int, error)
	MaskOldAddresses(ctx context.Context) (int64, error)
}

// Run 은 ctx 가 취소될 때까지 돈다. main 에서 `go scheduler.Run(...)` 으로 별도 고루틴에서 실행한다.
// 고루틴은 Java 의 스레드보다 훨씬 가벼운 실행 단위다 (수 KB).
func Run(ctx context.Context, every time.Duration, initialDelay time.Duration, messages Dispatcher) {
	select {
	case <-time.After(initialDelay):
	case <-ctx.Done():
		return
	}
	ticker := time.NewTicker(every)
	defer ticker.Stop()
	lastMaintenance := time.Time{}
	for {
		runOnce(ctx, messages)
		if time.Since(lastMaintenance) >= 24*time.Hour {
			maintain(ctx, messages)
			lastMaintenance = time.Now()
		}
		select {
		case <-ticker.C:
		case <-ctx.Done():
			return
		}
	}
}

func runOnce(ctx context.Context, messages Dispatcher) {
	// 한 주기가 너무 오래 걸리지 않게 상한을 둔다 (외부 API 가 멈춰도 다음 주기는 돈다)
	ctx, cancel := context.WithTimeout(ctx, 5*time.Minute)
	defer cancel()
	sent, err := messages.DispatchPending(ctx)
	if err != nil {
		slog.Error("발송 실패", "err", err)
	}
	if sent > 0 {
		slog.Info("알림 스케줄러", "sent", sent)
	}
}

func maintain(ctx context.Context, messages Dispatcher) {
	ctx, cancel := context.WithTimeout(ctx, time.Minute)
	defer cancel()
	n, err := messages.MaskOldAddresses(ctx)
	if err != nil {
		slog.Error("주소 가리기 실패", "err", err)
		return
	}
	if n > 0 {
		slog.Info("오래된 기록의 주소를 가림", "rows", n)
	}
}
