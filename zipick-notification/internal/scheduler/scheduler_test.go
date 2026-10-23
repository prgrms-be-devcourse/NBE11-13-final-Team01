package scheduler

import (
	"context"
	"testing"
	"time"
)

type fakeDispatcher struct{ dispatched, masked int }

func (f *fakeDispatcher) DispatchPending(context.Context) (int, error)    { f.dispatched++; return 1, nil }
func (f *fakeDispatcher) MaskOldAddresses(context.Context) (int64, error) { f.masked++; return 0, nil }

func TestRun_DispatchesEveryTickAndMasksOnce(t *testing.T) {
	d := &fakeDispatcher{}
	ctx, cancel := context.WithTimeout(context.Background(), 120*time.Millisecond)
	defer cancel()
	Run(ctx, 20*time.Millisecond, 0, d)
	if d.dispatched < 3 {
		t.Fatalf("주기마다 발송해야 함: %d", d.dispatched)
	}
	if d.masked != 1 {
		t.Fatalf("주소 가리기는 하루 한 번(시작 때 1회): %d", d.masked)
	}
}
