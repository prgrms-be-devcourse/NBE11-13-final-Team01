package message

import (
	"fmt"
	"time"
)

// quietHours 는 광고성 메시지 발송 금지 시간. 정보통신망법: 21시~익일 8시는 수신자 별도 동의 필요 → 다음 날 아침으로 미룬다.
type quietHours struct{ startH, startM, endH, endM int }

func parseQuietHours(start, end string) (quietHours, error) {
	var q quietHours
	if _, err := fmt.Sscanf(start, "%d:%d", &q.startH, &q.startM); err != nil {
		return q, fmt.Errorf("NOTIFICATION_QUIET_START 형식 오류 (예 21:00): %w", err)
	}
	if _, err := fmt.Sscanf(end, "%d:%d", &q.endH, &q.endM); err != nil {
		return q, fmt.Errorf("NOTIFICATION_QUIET_END 형식 오류 (예 08:00): %w", err)
	}
	return q, nil
}

// shift 는 금지 시간에 걸린 시각을 다음 허용 시각(아침)으로 옮긴다.
//
//	21:00~24:00 → 다음 날 08:00
//	00:00~08:00 → 같은 날 08:00
//	그 외       → 그대로
func (q quietHours) shift(t time.Time) time.Time {
	mins := t.Hour()*60 + t.Minute()
	start, end := q.startH*60+q.startM, q.endH*60+q.endM
	day := time.Date(t.Year(), t.Month(), t.Day(), 0, 0, 0, 0, t.Location())
	switch {
	case mins >= start:
		return day.AddDate(0, 0, 1).Add(time.Duration(end) * time.Minute)
	case mins < end:
		return day.Add(time.Duration(end) * time.Minute)
	}
	return t
}
