// Package message 는 아웃박스다.
// 요청이 오면 템플릿을 채워 notification_messages 에 적고(PENDING), 스케줄러가 scheduled_for 가 지난 것을 보낸다.
// dedupe_key 가 unique 라 같은 알림이 두 번 적히지 않는다. 실패는 max-attempts 까지 재시도.
// 수신자 주소는 보낼 때 구독에서 읽는다 — 그 사이 주소를 바꿨으면 새 주소로 간다.
package message

import (
	"context"
	"errors"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/zipick/notification/internal/db"
	"github.com/zipick/notification/internal/template"
)

// Status 는 아웃박스 상태.
type Status string

const (
	Pending   Status = "PENDING"
	Sent      Status = "SENT"
	Failed    Status = "FAILED"
	Skipped   Status = "SKIPPED"
	Cancelled Status = "CANCELLED"
)

// Message 는 notification_messages 한 행.
type Message struct {
	ID                int64
	UserID            *int64 // 가입 인증처럼 아직 계정이 없으면 nil
	TemplateCode      string
	Category          template.Category
	CampaignID        *int64
	DedupeKey         *string
	Variables         *string // 요청이 보낸 변수(JSON). 운영자가 "왜 이렇게 나갔나" 볼 때
	ToEmail           *string // 요청이 직접 지정한 수신 주소 (없으면 구독에서 읽음)
	ToSlackWebhookURL *string
	Title             string
	Body              string
	URL               *string
	ScheduledFor      time.Time
	Status            Status
	Attempts          int
	LastError         *string
	SentAt            *time.Time
	Channels          *string // 실제로 나간 채널 (예: "slack,email")
}

// Response 는 메인 서버의 NotificationEventResponse 와 같은 모양.
type Response struct {
	ID           int64             `json:"id"`
	UserID       *int64            `json:"userId"`
	TemplateCode string            `json:"templateCode"`
	Category     template.Category `json:"category"`
	CampaignID   *int64            `json:"campaignId"`
	Title        string            `json:"title"`
	Body         string            `json:"body"`
	URL          *string           `json:"url"`
	Status       Status            `json:"status"`
	Attempts     int               `json:"attempts"`
	LastError    *string           `json:"lastError"`
	Channels     *string           `json:"channels"`
	ScheduledFor db.LocalDateTime  `json:"scheduledFor"`
	SentAt       *db.LocalDateTime `json:"sentAt"`
	Duplicate    bool              `json:"duplicate"` // dedupeKey 가 같아 기존 건을 돌려준 경우 true
}

func ToResponse(m Message, duplicate bool) Response {
	return Response{ID: m.ID, UserID: m.UserID, TemplateCode: m.TemplateCode, Category: m.Category, CampaignID: m.CampaignID,
		Title: m.Title, Body: m.Body, URL: m.URL, Status: m.Status, Attempts: m.Attempts, LastError: m.LastError,
		Channels: m.Channels, ScheduledFor: db.LocalDateTime{Time: db.FromDB(m.ScheduledFor)}, SentAt: db.FromDBPtr(m.SentAt), Duplicate: duplicate}
}

// Repo 는 아웃박스 조회·저장. 트랜잭션이 필요한 발송 처리는 service.go 의 dispatchOne 에 있다.
type Repo struct{ pool *pgxpool.Pool }

func NewRepo(pool *pgxpool.Pool) *Repo { return &Repo{pool: pool} }

// Pool 은 캠페인 등 다른 패키지가 같은 연결 풀로 트랜잭션을 열 때 쓴다.
func (r *Repo) Pool() *pgxpool.Pool { return r.pool }

const cols = `id, user_id, template_code, category, campaign_id, dedupe_key, variables, to_email, to_slack_webhook_url,
	title, body, url, scheduled_for, status, attempts, last_error, sent_at, channels`

// querier 는 풀과 트랜잭션 양쪽에서 쓸 수 있게 하는 작은 인터페이스 (pgx.Tx 와 pgxpool.Pool 둘 다 만족).
type querier interface {
	QueryRow(ctx context.Context, sql string, args ...any) pgx.Row
	Query(ctx context.Context, sql string, args ...any) (pgx.Rows, error)
	Exec(ctx context.Context, sql string, args ...any) (pgconnCommandTag, error)
}

func scan(row pgx.Row) (Message, error) {
	var m Message
	err := row.Scan(&m.ID, &m.UserID, &m.TemplateCode, &m.Category, &m.CampaignID, &m.DedupeKey, &m.Variables, &m.ToEmail,
		&m.ToSlackWebhookURL, &m.Title, &m.Body, &m.URL, &m.ScheduledFor, &m.Status, &m.Attempts, &m.LastError, &m.SentAt, &m.Channels)
	return m, err
}

func (r *Repo) FindByDedupeKey(ctx context.Context, q querier, key string) (*Message, error) {
	m, err := scan(q.QueryRow(ctx, `select `+cols+` from notification_messages where dedupe_key = $1`, key))
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	return &m, nil
}

// Insert 는 새 행을 넣고 id 를 채워 돌려준다. (returning 으로 만들어진 id 를 바로 받는다)
func (r *Repo) Insert(ctx context.Context, q querier, m Message) (Message, error) {
	err := q.QueryRow(ctx, `
		insert into notification_messages (user_id, template_code, category, campaign_id, dedupe_key, variables, to_email, to_slack_webhook_url,
		  title, body, url, scheduled_for, status, attempts, created_at, updated_at)
		values ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,'PENDING',0,$13,$13) returning id`,
		m.UserID, m.TemplateCode, m.Category, m.CampaignID, m.DedupeKey, m.Variables, m.ToEmail, m.ToSlackWebhookURL,
		m.Title, m.Body, m.URL, m.ScheduledFor, db.Now()).Scan(&m.ID)
	m.Status, m.Attempts = Pending, 0
	return m, err
}

func (r *Repo) FindByID(ctx context.Context, q querier, id int64) (Message, error) {
	return scan(q.QueryRow(ctx, `select `+cols+` from notification_messages where id = $1`, id))
}

// LockPending 은 트랜잭션 안에서 행을 잠근다. SKIP LOCKED 덕분에 서버가 여러 대여도 같은 행을 둘이 보내지 않는다.
func (r *Repo) LockPending(ctx context.Context, tx pgx.Tx, id int64) (*Message, error) {
	m, err := scan(tx.QueryRow(ctx, `select `+cols+` from notification_messages where id = $1 and status = 'PENDING' for update skip locked`, id))
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	return &m, nil
}

// DuePendingIDs 는 때가 된 PENDING 메시지 id 를 오래된 순으로 최대 limit 개.
func (r *Repo) DuePendingIDs(ctx context.Context, now time.Time, limit int) ([]int64, error) {
	rows, err := r.pool.Query(ctx, `select id from notification_messages where status = 'PENDING' and scheduled_for <= $1 order by id limit $2`, now, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var ids []int64
	for rows.Next() {
		var id int64
		if err := rows.Scan(&id); err != nil {
			return nil, err
		}
		ids = append(ids, id)
	}
	return ids, rows.Err()
}

func (r *Repo) History(ctx context.Context, userID int64) ([]Message, error) {
	rows, err := r.pool.Query(ctx, `select `+cols+` from notification_messages where user_id = $1 order by id desc limit 50`, userID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []Message{}
	for rows.Next() {
		m, err := scan(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, m)
	}
	return out, rows.Err()
}

// 상태 변경 셋. 호출한 쪽의 트랜잭션(q) 안에서 실행된다.

func (r *Repo) MarkSent(ctx context.Context, q querier, id int64, channels string, at time.Time) error {
	_, err := q.Exec(ctx, `update notification_messages set status='SENT', attempts=attempts+1, channels=$2, sent_at=$3, last_error=null, updated_at=$3 where id=$1`, id, channels, at)
	return err
}

// MarkFailed 는 attempts 를 올리고, 상한에 닿았을 때만 FAILED 로 바꾼다 (그 전까지는 PENDING 으로 남아 재시도).
func (r *Repo) MarkFailed(ctx context.Context, q querier, id int64, reason string, maxAttempts int) error {
	_, err := q.Exec(ctx, `update notification_messages set attempts=attempts+1, last_error=$2,
		status = case when attempts+1 >= $3 then 'FAILED' else status end, updated_at=$4 where id=$1`, id, cut(reason, 500), maxAttempts, db.Now())
	return err
}

func (r *Repo) MarkSkipped(ctx context.Context, q querier, id int64, why string) error {
	_, err := q.Exec(ctx, `update notification_messages set status='SKIPPED', last_error=$2, updated_at=$3 where id=$1`, id, cut(why, 500), db.Now())
	return err
}

// CancelPendingOfCampaign 은 캠페인 취소 때 아직 안 나간 메시지만 취소한다.
func (r *Repo) CancelPendingOfCampaign(ctx context.Context, campaignID int64) (int64, error) {
	tag, err := r.pool.Exec(ctx, `update notification_messages set status='CANCELLED', updated_at=$2 where campaign_id=$1 and status='PENDING'`, campaignID, db.Now())
	return tag.RowsAffected(), err
}

// CountByCampaign 은 캠페인 결과 집계 (상태별 건수).
func (r *Repo) CountByCampaign(ctx context.Context, campaignID int64) (map[Status]int64, error) {
	rows, err := r.pool.Query(ctx, `select status, count(*) from notification_messages where campaign_id = $1 group by status`, campaignID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := map[Status]int64{}
	for rows.Next() {
		var s Status
		var n int64
		if err := rows.Scan(&s, &n); err != nil {
			return nil, err
		}
		out[s] = n
	}
	return out, rows.Err()
}

func cut(s string, n int) string {
	if len(s) <= n {
		return s
	}
	return s[:n]
}
