package message

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"strings"

	"github.com/jackc/pgx/v5"
	"github.com/zipick/notification/internal/db"
	"github.com/zipick/notification/internal/httpx"
)

// 묶음 요청: 같은 템플릿·채널·시각으로 수신자 여러 명. 누구에게 보낼지(대상 선정)는 요청하는 쪽이 정해서 온다.
// 알림센터는 받은 목록을 한 번에 적고, 묶음 ID 로 취소·조회만 한다.

// BatchRecipient 는 수신자 한 명.
type BatchRecipient struct {
	To        Recipient         `json:"to"`
	Variables map[string]any    `json:"variables"`
	DedupeKey *string           `json:"dedupeKey"`
	Tags      map[string]string `json:"tags"` // 묶음 tags 위에 덧씌운다 (예 user:2 — 사용자별 내역 조회용)
}

// BatchRequest 는 POST /internal/v1/batches 본문.
type BatchRequest struct {
	Source       string            `json:"source"`
	TemplateCode string            `json:"templateCode"`
	Channels     []string          `json:"channels"`
	ScheduledAt  *db.LocalDateTime `json:"scheduledAt"`
	Tags         map[string]string `json:"tags"`
	Recipients   []BatchRecipient  `json:"recipients"`
}

// BatchResponse 는 묶음 상태와 집계.
type BatchResponse struct {
	ID           int64             `json:"id"`
	Source       string            `json:"source"`
	TemplateCode string            `json:"templateCode"`
	Channels     []string          `json:"channels"`
	ScheduledAt  db.LocalDateTime  `json:"scheduledAt"`
	Status       string            `json:"status"` // SCHEDULED | CANCELLED
	Tags         map[string]string `json:"tags"`
	Total        int64             `json:"total"`
	Pending      int64             `json:"pending"`
	Sent         int64             `json:"sent"`
	Partial      int64             `json:"partial"`
	Failed       int64             `json:"failed"`
	Cancelled    int64             `json:"cancelled"`
	Skipped      int64             `json:"skippedDuplicates"` // dedupeKey 가 이미 있어 적지 않은 수신자
	CreatedAt    db.LocalDateTime  `json:"createdAt"`
}

const maxBatchRecipients = 5000

// CreateBatch 는 묶음을 한 트랜잭션으로 적는다. 수신자 하나라도 검사에 걸리면 전체를 거부한다 (반쯤 적히지 않게).
func (s *Service) CreateBatch(ctx context.Context, r BatchRequest) (BatchResponse, error) {
	if len(r.Recipients) == 0 || len(r.Recipients) > maxBatchRecipients {
		return BatchResponse{}, httpx.Fail(httpx.InvalidInput, fmt.Sprintf("recipients 는 1~%d명이어야 합니다", maxBatchRecipients))
	}
	// 수신자마다 같은 검사를 거친다 (주소·채널·템플릿 문구)
	for i, rc := range r.Recipients {
		if _, err := s.Validate(Request{Source: r.Source, TemplateCode: r.TemplateCode, Channels: r.Channels, To: rc.To,
			Variables: rc.Variables, DedupeKey: rc.DedupeKey, ScheduledAt: r.ScheduledAt, Tags: r.Tags}); err != nil {
			var e *httpx.Error
			if errors.As(err, &e) {
				return BatchResponse{}, httpx.Fail(e.Code, fmt.Sprintf("recipients[%d]: %s", i, e.Message))
			}
			return BatchResponse{}, err
		}
	}
	tpl, _ := s.templates.Get(r.TemplateCode)
	when := db.Now()
	if r.ScheduledAt != nil && !r.ScheduledAt.IsZero() {
		when = r.ScheduledAt.Time
	}
	tags, _ := json.Marshal(r.Tags)
	if r.Tags == nil {
		tags = []byte("{}")
	}

	tx, err := s.repo.pool.Begin(ctx)
	if err != nil {
		return BatchResponse{}, err
	}
	defer tx.Rollback(ctx)
	var batchID int64
	if err := tx.QueryRow(ctx, `insert into notification_batches (source, template_code, channels, scheduled_for, status, tags, created_at, updated_at)
		values ($1,$2,$3,$4,'SCHEDULED',$5::jsonb,$6,$6) returning id`,
		r.Source, tpl.Code, strings.Join(r.Channels, ","), when, string(tags), db.Now()).Scan(&batchID); err != nil {
		return BatchResponse{}, err
	}
	skipped := int64(0)
	for _, rc := range r.Recipients {
		if rc.DedupeKey != nil {
			if existing, err := s.repo.FindByDedupeKey(ctx, tx, *rc.DedupeKey); err != nil {
				return BatchResponse{}, err
			} else if existing != nil {
				skipped++
				continue
			}
		}
		req := Request{Source: r.Source, TemplateCode: tpl.Code, Channels: r.Channels, To: rc.To, Variables: rc.Variables,
			DedupeKey: rc.DedupeKey, ScheduledAt: r.ScheduledAt, Tags: mergeTags(r.Tags, rc.Tags)}
		if _, err := s.repo.Insert(ctx, tx, Build(tpl, req, &batchID)); err != nil {
			return BatchResponse{}, err
		}
	}
	if _, err := tx.Exec(ctx, `update notification_batches set skipped_duplicates=$2 where id=$1`, batchID, skipped); err != nil {
		return BatchResponse{}, err
	}
	if err := tx.Commit(ctx); err != nil {
		return BatchResponse{}, err
	}
	return s.GetBatch(ctx, batchID)
}

func (s *Service) GetBatch(ctx context.Context, id int64) (BatchResponse, error) {
	var b BatchResponse
	var channels string
	var tags []byte
	var scheduled, created = db.Now(), db.Now()
	err := s.repo.pool.QueryRow(ctx, `select id, source, template_code, channels, scheduled_for, status, tags, skipped_duplicates, created_at
		from notification_batches where id = $1`, id).Scan(&b.ID, &b.Source, &b.TemplateCode, &channels, &scheduled, &b.Status, &tags, &b.Skipped, &created)
	if errors.Is(err, pgx.ErrNoRows) {
		return b, httpx.Fail(httpx.BatchNotFound, fmt.Sprintf("묶음이 없습니다: %d", id))
	}
	if err != nil {
		return b, err
	}
	b.Channels = splitCSV(channels)
	b.Tags = map[string]string{}
	_ = json.Unmarshal(tags, &b.Tags)
	b.ScheduledAt, b.CreatedAt = db.LocalDateTime{Time: db.FromDB(scheduled)}, db.LocalDateTime{Time: db.FromDB(created)}
	counts, err := s.repo.CountByBatch(ctx, id)
	if err != nil {
		return b, err
	}
	b.Pending, b.Sent, b.Partial, b.Failed, b.Cancelled = counts[Pending], counts[Sent], counts[Partial], counts[Failed], counts[Cancelled]
	b.Total = b.Pending + b.Sent + b.Partial + b.Failed + b.Cancelled
	return b, nil
}

// CancelBatch 는 아직 안 나간 메시지를 취소한다. 이미 나간 것은 그대로.
func (s *Service) CancelBatch(ctx context.Context, id int64) (BatchResponse, error) {
	if _, err := s.GetBatch(ctx, id); err != nil {
		return BatchResponse{}, err
	}
	if _, err := s.repo.CancelPendingOfBatch(ctx, id); err != nil {
		return BatchResponse{}, err
	}
	if _, err := s.repo.pool.Exec(ctx, `update notification_batches set status='CANCELLED', updated_at=$2 where id=$1`, id, db.Now()); err != nil {
		return BatchResponse{}, err
	}
	return s.GetBatch(ctx, id)
}

// mergeTags 는 묶음 tags 위에 수신자 tags 를 덧씌운 새 맵.
func mergeTags(base, over map[string]string) map[string]string {
	out := make(map[string]string, len(base)+len(over))
	for k, v := range base {
		out[k] = v
	}
	for k, v := range over {
		out[k] = v
	}
	return out
}
