package db

import (
	"testing"
	"time"
)

func TestFromDB_UTC_라벨의_벽시계를_서울로_다시_붙인다(t *testing.T) {
	in := time.Date(2026, 10, 20, 10, 6, 0, 0, time.UTC)
	got := FromDB(in)
	if got.Hour() != 10 || got.Location() != Seoul {
		t.Fatalf("%v", got)
	}
	if FromDBPtr(nil) != nil || FromDBPtr(&in).Hour() != 10 {
		t.Fatal("FromDBPtr")
	}
}

func TestPtr와_Now(t *testing.T) {
	if Ptr(time.Time{}) != nil || Ptr(time.Now()) == nil {
		t.Fatal("Ptr")
	}
	if Now().Location() != Seoul {
		t.Fatal("Now 는 서울 시각")
	}
}

func TestLocalDateTime_잘못된_값은_오류(t *testing.T) {
	var v LocalDateTime
	if v.UnmarshalJSON([]byte(`"어제"`)) == nil {
		t.Fatal("잘못된 시각 통과")
	}
	if v.UnmarshalJSON([]byte(`null`)) != nil || !v.IsZero() {
		t.Fatal("null 은 빈 값")
	}
	if v.UnmarshalJSON([]byte(`"2026-10-21T08:00"`)) != nil || v.Hour() != 8 {
		t.Fatal("분까지만 있는 형식")
	}
}
