package scheduler

import (
	"context"
	"errors"
	"sync/atomic"
	"testing"
	"time"
)

type step struct {
	calls atomic.Int32
	n     int
	err   error
}

func (s *step) QueueDue(context.Context) (int, error)        { s.calls.Add(1); return s.n, s.err }
func (s *step) DispatchPending(context.Context) (int, error) { s.calls.Add(1); return s.n, s.err }

func TestRunOnce_캠페인과_발송을_한_번씩(t *testing.T) {
	c, m := &step{n: 2}, &step{n: 1}
	runOnce(context.Background(), c, m)
	if c.calls.Load() != 1 || m.calls.Load() != 1 {
		t.Fatalf("calls %d %d", c.calls.Load(), m.calls.Load())
	}
}

func TestRunOnce_한쪽이_실패해도_다른_쪽은_돈다(t *testing.T) {
	c, m := &step{err: errors.New("x")}, &step{}
	runOnce(context.Background(), c, m)
	if m.calls.Load() != 1 {
		t.Fatal("캠페인 실패 뒤 발송이 안 돎")
	}
}

func TestRun_주기마다_돌고_취소되면_멈춘다(t *testing.T) {
	c, m := &step{}, &step{}
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	go func() { Run(ctx, 10*time.Millisecond, 0, c, m); close(done) }()
	time.Sleep(55 * time.Millisecond)
	cancel()
	<-done
	if c.calls.Load() < 2 {
		t.Fatalf("주기 실행 횟수 %d", c.calls.Load())
	}
}

func TestRun_첫_대기_중에_취소되면_한_번도_돌지_않는다(t *testing.T) {
	c, m := &step{}, &step{}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	Run(ctx, time.Second, time.Hour, c, m)
	if c.calls.Load() != 0 {
		t.Fatal("취소됐는데 실행됨")
	}
}
