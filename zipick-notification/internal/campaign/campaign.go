// Package campaign 은 예약 발송이다.
// 운영자가 "이 템플릿을, 이 대상에게, 이 시각에"를 정해 두면 스케줄러가 그 시각에 대상을 풀어 아웃박스에 적는다(QUEUED).
// 그 뒤는 일반 메시지와 같다. 시각 전에는 취소할 수 있다. 적힌 뒤 취소하면 아직 안 나간 메시지만 취소된다.
package campaign

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"strconv"
	"strings"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/zipick/notification/internal/db"
	"github.com/zipick/notification/internal/httpx"
	"github.com/zipick/notification/internal/message"
	"github.com/zipick/notification/internal/subscription"
	"github.com/zipick/notification/internal/template"
)

// Audience 는 누구에게 보낼지.
//
//	MARKETING_CONSENTED : 광고 수신 동의자 전체 (광고 템플릿은 이것만 허용)
//	ENABLED             : 정보성 알림이 켜진 사용자 전체 (공지 등)
//	USER_IDS            : 지정한 사용자들
type Audience string

const (
	MarketingConsented Audience = "MARKETING_CONSENTED"
	Enabled            Audience = "ENABLED"
	UserIDs            Audience = "USER_IDS"
)

type Status string

const (
	Scheduled Status = "SCHEDULED"
	Queued    Status = "QUEUED"
	Cancelled Status = "CANCELLED"
)

type Campaign struct {
	ID           int64
	Name         string
	TemplateCode string
	Variables    *string // 모든 수신자에게 같은 변수 (JSON)
	Audience     Audience
	UserIDs      *string // audience=USER_IDS 일 때 쉼표 구분
	ScheduledAt  time.Time
	Status       Status
	CreatedBy    *string
	QueuedCount  int
	QueuedAt     *time.Time
}

func (c Campaign) userIDList() []int64 {
	if c.UserIDs == nil {
		return []int64{}
	}
	seen := map[int64]bool{}
	out := []int64{}
	for _, p := range strings.Split(*c.UserIDs, ",") {
		n, err := strconv.ParseInt(strings.TrimSpace(p), 10, 64)
		if err != nil || seen[n] {
			continue
		}
		seen[n] = true
		out = append(out, n)
	}
	return out
}

type Request struct {
	Name         string            `json:"name"`
	TemplateCode string            `json:"templateCode"`
	Variables    map[string]any    `json:"variables"`
	Audience     Audience          `json:"audience"`
	UserIDs      []int64           `json:"userIds"`
	ScheduledAt  *db.LocalDateTime `json:"scheduledAt"` // 비우면 바로 (다음 주기)
	CreatedBy    *string           `json:"createdBy"`
}

type Response struct {
	ID           int64             `json:"id"`
	Name         string            `json:"name"`
	TemplateCode string            `json:"templateCode"`
	Variables    *string           `json:"variables"`
	Audience     Audience          `json:"audience"`
	UserIDs      []int64           `json:"userIds"`
	ScheduledAt  db.LocalDateTime  `json:"scheduledAt"`
	Status       Status            `json:"status"`
	CreatedBy    *string           `json:"createdBy"`
	QueuedCount  int               `json:"queuedCount"`
	QueuedAt     *db.LocalDateTime `json:"queuedAt"`
	SentCount    int64             `json:"sentCount"`
	PendingCount int64             `json:"pendingCount"`
	FailedCount  int64             `json:"failedCount"`
	SkippedCount int64             `json:"skippedCount"`
}

// Service 는 등록·조회·취소·(스케줄러) 적재.
type Service struct {
	pool      *pgxpool.Pool
	messages  *message.Repo
	msgSvc    *message.Service
	subs      *subscription.Repo
	templates *template.Repo
}

func NewService(pool *pgxpool.Pool, messages *message.Repo, msgSvc *message.Service, subs *subscription.Repo, templates *template.Repo) *Service {
	return &Service{pool: pool, messages: messages, msgSvc: msgSvc, subs: subs, templates: templates}
}

const cols = `id, name, template_code, variables, audience, user_ids, scheduled_at, status, created_by, queued_count, queued_at`

func scan(row pgx.Row) (Campaign, error) {
	var c Campaign
	err := row.Scan(&c.ID, &c.Name, &c.TemplateCode, &c.Variables, &c.Audience, &c.UserIDs, &c.ScheduledAt, &c.Status, &c.CreatedBy, &c.QueuedCount, &c.QueuedAt)
	return c, err
}

func (s *Service) Create(ctx context.Context, r Request) (Response, error) {
	switch {
	case strings.TrimSpace(r.Name) == "" || len(r.Name) > 100:
		return Response{}, httpx.Fail(httpx.InvalidInput, "name 은 1~100자여야 합니다.")
	case r.Audience != MarketingConsented && r.Audience != Enabled && r.Audience != UserIDs:
		return Response{}, httpx.Fail(httpx.InvalidInput, "audience 는 MARKETING_CONSENTED, ENABLED, USER_IDS 중 하나여야 합니다.")
	case r.Audience == UserIDs && len(r.UserIDs) == 0:
		return Response{}, httpx.Fail(httpx.InvalidInput, "USER_IDS 대상은 userIds 가 필요합니다.")
	}
	t, err := s.templates.FindByCode(ctx, r.TemplateCode)
	if err != nil {
		return Response{}, err
	}
	if t.Category == template.Marketing && r.Audience != MarketingConsented {
		return Response{}, httpx.Fail(httpx.InvalidInput, "광고 템플릿은 광고 수신 동의자(MARKETING_CONSENTED)에게만 보낼 수 있습니다.")
	}
	var vars *string
	if r.Variables != nil {
		b, _ := json.Marshal(r.Variables)
		vs := string(b)
		vars = &vs
	}
	var ids *string
	if len(r.UserIDs) > 0 {
		parts := make([]string, 0, len(r.UserIDs))
		for _, id := range r.UserIDs {
			parts = append(parts, strconv.FormatInt(id, 10))
		}
		joined := strings.Join(parts, ",")
		ids = &joined
	}
	when := db.Now()
	if r.ScheduledAt != nil {
		when = r.ScheduledAt.Time
	}
	var id int64
	err = s.pool.QueryRow(ctx, `insert into campaigns (name, template_code, variables, audience, user_ids, scheduled_at, status, created_by, queued_count, created_at, updated_at)
		values ($1,$2,$3,$4,$5,$6,'SCHEDULED',$7,0,$8,$8) returning id`, r.Name, t.Code, vars, r.Audience, ids, when, r.CreatedBy, db.Now()).Scan(&id)
	if err != nil {
		return Response{}, err
	}
	return s.Get(ctx, id)
}

func (s *Service) Get(ctx context.Context, id int64) (Response, error) {
	c, err := scan(s.pool.QueryRow(ctx, `select `+cols+` from campaigns where id = $1`, id))
	if errors.Is(err, pgx.ErrNoRows) {
		return Response{}, httpx.Fail(httpx.CampaignNotFound, "캠페인을 찾을 수 없습니다.")
	}
	if err != nil {
		return Response{}, err
	}
	return s.toResponse(ctx, c)
}

func (s *Service) List(ctx context.Context) ([]Response, error) {
	rows, err := s.pool.Query(ctx, `select `+cols+` from campaigns order by id desc limit 100`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []Response{}
	for rows.Next() {
		c, err := scan(rows)
		if err != nil {
			return nil, err
		}
		res, err := s.toResponse(ctx, c)
		if err != nil {
			return nil, err
		}
		out = append(out, res)
	}
	return out, rows.Err()
}

// Cancel: 시각 전이면 캠페인만 취소, 이미 적혔으면 아직 안 나간 메시지도 취소.
func (s *Service) Cancel(ctx context.Context, id int64) (Response, error) {
	cur, err := s.Get(ctx, id)
	if err != nil {
		return Response{}, err
	}
	if cur.Status == Cancelled {
		return cur, nil
	}
	if cur.Status == Queued {
		if _, err := s.messages.CancelPendingOfCampaign(ctx, id); err != nil {
			return Response{}, err
		}
	}
	if _, err := s.pool.Exec(ctx, `update campaigns set status='CANCELLED', updated_at=$2 where id=$1`, id, db.Now()); err != nil {
		return Response{}, err
	}
	return s.Get(ctx, id)
}

// QueueDue 는 스케줄러가 부른다: 시각이 된 캠페인을 풀어 아웃박스에 적는다. 반환: 적은 메시지 수.
func (s *Service) QueueDue(ctx context.Context) (int, error) {
	rows, err := s.pool.Query(ctx, `select `+cols+` from campaigns where status='SCHEDULED' and scheduled_at <= $1 order by id`, db.Now())
	if err != nil {
		return 0, err
	}
	var due []Campaign
	for rows.Next() {
		c, err := scan(rows)
		if err != nil {
			rows.Close()
			return 0, err
		}
		due = append(due, c)
	}
	rows.Close()

	total := 0
	for _, c := range due {
		n, err := s.queueOne(ctx, c)
		if err != nil {
			slog.Error("캠페인 적재 실패", "campaign", c.ID, "err", err)
			continue
		}
		total += n
	}
	return total, nil
}

// queueOne 은 캠페인 하나를 트랜잭션 안에서 푼다. 중간에 실패하면 전부 되돌려 다음 주기에 다시 시도한다.
func (s *Service) queueOne(ctx context.Context, c Campaign) (int, error) {
	t, err := s.templates.FindByCode(ctx, c.TemplateCode)
	if err != nil {
		return 0, err
	}
	vars := map[string]any{}
	if c.Variables != nil {
		if err := json.Unmarshal([]byte(*c.Variables), &vars); err != nil {
			return 0, fmt.Errorf("캠페인 변수 JSON 오류: %w", err)
		}
	}
	var targets []subscription.Subscription
	switch c.Audience {
	case MarketingConsented:
		targets, err = s.subs.FindMarketingConsented(ctx)
	case Enabled:
		targets, err = s.subs.FindEnabled(ctx)
	case UserIDs:
		targets, err = s.subs.FindByUserIDs(ctx, c.userIDList())
	}
	if err != nil {
		return 0, err
	}
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return 0, err
	}
	defer tx.Rollback(ctx)
	count := 0
	for _, sub := range targets {
		key := fmt.Sprintf("CAMPAIGN:%d:%d", c.ID, sub.UserID)
		if existing, err := s.messages.FindByDedupeKey(ctx, tx, key); err != nil {
			return 0, err
		} else if existing != nil {
			continue
		}
		uid := sub.UserID
		cid := c.ID
		if _, err := s.messages.Insert(ctx, tx, s.msgSvc.Build(t, &uid, nil, nil, vars, &key, db.FromDB(c.ScheduledAt), &cid)); err != nil {
			return 0, err
		}
		count++
	}
	if _, err := tx.Exec(ctx, `update campaigns set status='QUEUED', queued_count=$2, queued_at=$3, updated_at=$3 where id=$1`, c.ID, count, db.Now()); err != nil {
		return 0, err
	}
	if err := tx.Commit(ctx); err != nil {
		return 0, err
	}
	slog.Info("캠페인 적음", "campaign", c.ID, "name", c.Name, "targets", len(targets), "queued", count)
	return count, nil
}

func (s *Service) toResponse(ctx context.Context, c Campaign) (Response, error) {
	counts, err := s.messages.CountByCampaign(ctx, c.ID)
	if err != nil {
		return Response{}, err
	}
	return Response{ID: c.ID, Name: c.Name, TemplateCode: c.TemplateCode, Variables: c.Variables, Audience: c.Audience,
		UserIDs: c.userIDList(), ScheduledAt: db.LocalDateTime{Time: db.FromDB(c.ScheduledAt)}, Status: c.Status, CreatedBy: c.CreatedBy,
		QueuedCount: c.QueuedCount, QueuedAt: db.FromDBPtr(c.QueuedAt),
		SentCount: counts[message.Sent], PendingCount: counts[message.Pending], FailedCount: counts[message.Failed], SkippedCount: counts[message.Skipped]}, nil
}
