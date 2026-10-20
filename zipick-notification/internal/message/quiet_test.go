package message

import (
	"testing"
	"time"

	"github.com/zipick/notification/internal/db"
)

func at(y, m, d, h, min int) time.Time { return time.Date(y, time.Month(m), d, h, min, 0, 0, db.Seoul) }

func TestQuietHours(t *testing.T) {
	q, err := parseQuietHours("21:00", "08:00")
	if err != nil {
		t.Fatal(err)
	}
	cases := []struct{ in, want time.Time }{
		{at(2026, 10, 20, 14, 0), at(2026, 10, 20, 14, 0)}, // 낮: 그대로
		{at(2026, 10, 20, 21, 0), at(2026, 10, 21, 8, 0)},  // 21시: 다음 날 8시
		{at(2026, 10, 20, 23, 59), at(2026, 10, 21, 8, 0)},
		{at(2026, 10, 21, 2, 30), at(2026, 10, 21, 8, 0)}, // 새벽: 같은 날 8시
		{at(2026, 10, 21, 8, 0), at(2026, 10, 21, 8, 0)},  // 경계 8시 정각: 그대로
	}
	for _, c := range cases {
		if got := q.shift(c.in); !got.Equal(c.want) {
			t.Errorf("shift(%v) = %v, want %v", c.in, got, c.want)
		}
	}
}

func TestCutRunes(t *testing.T) {
	if got := cutRunes("가나다라", 2); got != "가나" {
		t.Fatalf("got %q", got)
	}
}
