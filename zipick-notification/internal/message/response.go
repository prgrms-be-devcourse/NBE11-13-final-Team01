package message

import "github.com/zipick/notification/internal/db"

// DeliveryResponse 는 채널 하나의 결과.
type DeliveryResponse struct {
	Channel   string            `json:"channel"`
	Status    DeliveryStatus    `json:"status"`
	Attempts  int               `json:"attempts"`
	LastError *string           `json:"lastError"`
	SentAt    *db.LocalDateTime `json:"sentAt"`
}

// Response 는 메시지 응답. 메인 서버의 NotificationEventResponse 와 같은 모양으로 유지한다.
type Response struct {
	ID               int64              `json:"id"`
	Source           string             `json:"source"`
	TemplateCode     string             `json:"templateCode"`
	BatchID          *int64             `json:"batchId"`
	Channels         []string           `json:"channels"`
	Title            string             `json:"title"` // 이메일 제목 또는 슬랙 첫 줄 (화면 표시용)
	Status           Status             `json:"status"`
	Deliveries       []DeliveryResponse `json:"deliveries"`
	MissingVariables []string           `json:"missingVariables"`
	Tags             map[string]string  `json:"tags"`
	ScheduledAt      db.LocalDateTime   `json:"scheduledAt"`
	CreatedAt        db.LocalDateTime   `json:"createdAt"`
	Duplicate        bool               `json:"duplicate"` // dedupeKey 가 같아 기존 건을 돌려준 경우 true
}

func ToResponse(m Message, ds []Delivery, duplicate bool) Response {
	out := make([]DeliveryResponse, 0, len(ds))
	for _, d := range ds {
		out = append(out, DeliveryResponse{Channel: d.Channel, Status: d.Status, Attempts: d.Attempts, LastError: d.LastError, SentAt: db.FromDBPtr(d.SentAt)})
	}
	title := deref(m.EmailSubject)
	if title == "" {
		title = firstLine(deref(m.SlackText))
	}
	tags := m.Tags
	if tags == nil {
		tags = map[string]string{}
	}
	return Response{ID: m.ID, Source: m.Source, TemplateCode: m.TemplateCode, BatchID: m.BatchID, Channels: m.Channels, Title: title,
		Status: m.Status, Deliveries: out, MissingVariables: m.MissingVariables, Tags: tags,
		ScheduledAt: db.LocalDateTime{Time: db.FromDB(m.ScheduledFor)}, CreatedAt: db.LocalDateTime{Time: db.FromDB(m.CreatedAt)}, Duplicate: duplicate}
}

func firstLine(s string) string {
	for i, c := range s {
		if c == '\n' {
			return s[:i]
		}
	}
	return s
}
