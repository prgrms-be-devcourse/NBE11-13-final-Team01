package db

import (
	"strings"
	"time"
)

// Seoul 은 모든 시각의 기준. 알림 시각·D-day 계산은 한국 시간으로 한다.
var Seoul = mustLoad("Asia/Seoul")

func mustLoad(name string) *time.Location {
	loc, err := time.LoadLocation(name)
	if err != nil {
		panic(err)
	}
	return loc
}

// Now 는 한국 시각. 컨테이너 TZ 설정과 무관하게 같은 값이 나온다.
func Now() time.Time { return time.Now().In(Seoul) }

// LocalDateTime 은 Java 의 LocalDateTime 처럼 "시간대 없는 시각"을 JSON 으로 주고받기 위한 타입이다.
//
//	Java  → "2026-10-20T10:06:46.543519"      (Z 나 +09:00 이 없다)
//	Go 기본 time.Time 은 "2026-10-20T10:06:46+09:00" 으로 써서 Jackson 이 LocalDateTime 으로 못 읽는다.
//
// DB 의 timestamp(시간대 없음) 컬럼도 같은 뜻이라, 저장·조회 모두 이 타입을 거친다.
// pgx 는 timestamp 컬럼을 "벽시계 값 그대로"(UTC 라벨) 넣고 꺼내므로, 항상 Seoul 벽시계 값을 넣으면 Spring 버전과 같은 값이 저장된다.
type LocalDateTime struct{ time.Time }

const layoutOut = "2006-01-02T15:04:05.999999"

// 받을 때는 소수점 유무, Z/오프셋 유무를 모두 허용한다 (Jackson, JS Date 둘 다 보낼 수 있게).
var layoutsIn = []string{
	"2006-01-02T15:04:05.999999999", "2006-01-02T15:04:05",
	"2006-01-02T15:04:05.999999999Z07:00", "2006-01-02T15:04:05Z07:00", "2006-01-02T15:04",
}

func (t LocalDateTime) MarshalJSON() ([]byte, error) {
	if t.IsZero() {
		return []byte("null"), nil
	}
	return []byte(`"` + t.Format(layoutOut) + `"`), nil
}

func (t *LocalDateTime) UnmarshalJSON(b []byte) error {
	s := strings.Trim(string(b), `"`)
	if s == "" || s == "null" {
		t.Time = time.Time{}
		return nil
	}
	for _, l := range layoutsIn {
		if parsed, err := time.ParseInLocation(l, s, Seoul); err == nil {
			// 오프셋이 있었다면 한국 벽시계로 바꾼 뒤 시간대를 떼어 낸다
			parsed = parsed.In(Seoul)
			t.Time = time.Date(parsed.Year(), parsed.Month(), parsed.Day(), parsed.Hour(), parsed.Minute(), parsed.Second(), parsed.Nanosecond(), Seoul)
			return nil
		}
	}
	return &time.ParseError{Layout: layoutOut, Value: s}
}

// Ptr 는 null 가능 컬럼/필드용. 값이 없으면 nil → JSON null.
func Ptr(t time.Time) *LocalDateTime {
	if t.IsZero() {
		return nil
	}
	return &LocalDateTime{Time: t}
}

// FromDB 는 pgx 가 돌려준 timestamp(UTC 라벨의 벽시계 값)를 Seoul 벽시계로 다시 라벨만 바꾼다.
func FromDB(t time.Time) time.Time {
	return time.Date(t.Year(), t.Month(), t.Day(), t.Hour(), t.Minute(), t.Second(), t.Nanosecond(), Seoul)
}

// FromDBPtr 는 null 가능 timestamp 용.
func FromDBPtr(t *time.Time) *LocalDateTime {
	if t == nil {
		return nil
	}
	return &LocalDateTime{Time: FromDB(*t)}
}
