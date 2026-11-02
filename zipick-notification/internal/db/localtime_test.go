package db

import (
	"encoding/json"
	"testing"
	"time"
)

func TestLocalDateTimeJSON(t *testing.T) {
	// Java 가 보내는 형식(시간대 없음, 소수점 있음)을 읽고, 같은 형식으로 쓴다
	var v struct {
		At LocalDateTime `json:"at"`
	}
	if err := json.Unmarshal([]byte(`{"at":"2026-10-20T10:06:46.543519"}`), &v); err != nil {
		t.Fatal(err)
	}
	if v.At.Hour() != 10 || v.At.Location() != Seoul {
		t.Fatalf("got %v", v.At)
	}
	out, _ := json.Marshal(v)
	if string(out) != `{"at":"2026-10-20T10:06:46.543519"}` {
		t.Fatalf("got %s", out)
	}
	// 오프셋이 있으면 한국 시각으로 바꾼다
	if err := json.Unmarshal([]byte(`{"at":"2026-10-20T01:00:00Z"}`), &v); err != nil || v.At.Hour() != 10 {
		t.Fatalf("got %v %v", v.At, err)
	}
	// 없으면 null
	empty, _ := json.Marshal(struct {
		At *LocalDateTime `json:"at"`
	}{Ptr(time.Time{})})
	if string(empty) != `{"at":null}` {
		t.Fatalf("got %s", empty)
	}
}
