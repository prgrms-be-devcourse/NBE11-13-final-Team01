// Package message 는 아웃박스다. 요청을 받아 문구를 채워 적고(PENDING), 때가 되면 채널별로 보낸다.
//
// 알림센터가 판단하는 것은 없다. 누구에게·언제·보내도 되는지는 요청이 다 정해서 온다.
// 여기서 하는 일은: 요청 검사 → 템플릿 채우기 → 적기 → 채널별로 보내기 → 결과 기록. 그뿐이다.
//
// 테이블 두 개:
//   - notification_messages   요청 하나 (문구가 채워진 상태로 저장)
//   - notification_deliveries 메시지 × 채널. 채널마다 따로 재시도하고, 성공한 채널로는 다시 보내지 않는다
package message

import (
	"context"
	"encoding/json"
	"errors"
	"strings"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/zipick/notification/internal/db"
)

// Status 는 메시지 상태. 채널별 결과를 모아 정한다.
//
//	PENDING   아직 안 보낸 채널이 있음
//	SENT      모든 채널 성공
//	PARTIAL   일부 채널만 성공, 나머지는 실패(재시도 끝)
//	FAILED    모든 채널 실패
//	CANCELLED 보내기 전에 취소됨 (묶음 취소)
type Status string

const (
	Pending   Status = "PENDING"
	Sent      Status = "SENT"
	Partial   Status = "PARTIAL"
	Failed    Status = "FAILED"
	Cancelled Status = "CANCELLED"
)

// DeliveryStatus 는 채널 하나의 상태.
type DeliveryStatus string

const (
	DPending DeliveryStatus = "PENDING"
	DSent    DeliveryStatus = "SENT"
	DFailed  DeliveryStatus = "FAILED"
)

// Message 는 notification_messages 한 행.
type Message struct {
	ID                int64
	Source            string
	TemplateCode      string
	BatchID           *int64
	DedupeKey         *string
	Channels          []string // 요청한 채널
	ToEmail           *string
	ToSlackWebhookURL *string
	Variables         string // 요청이 보낸 변수 (JSON). "왜 이렇게 나갔나" 볼 때
	MissingVariables  []string
	EmailSubject      *string // 채워진 문구 (채널 문구가 없으면 nil)
	EmailBody         *string
	SlackText         *string
	Tags              map[string]string
	ScheduledFor      time.Time
	Status            Status
	CreatedAt         time.Time
}

// Delivery 는 notification_deliveries 한 행.
type Delivery struct {
	ID        int64
	MessageID int64
	Channel   string
	Status    DeliveryStatus
	Attempts  int
	LastError *string
	SentAt    *time.Time
}

// Repo 는 SQL 모음. 트랜잭션이 필요한 발송은 service.go 가 tx 를 넘겨 부른다.
type Repo struct{ pool *pgxpool.Pool }

func NewRepo(pool *pgxpool.Pool) *Repo { return &Repo{pool: pool} }
func (r *Repo) Pool() *pgxpool.Pool    { return r.pool }

// querier 는 풀과 트랜잭션 양쪽에서 쓸 수 있게 하는 작은 인터페이스 (pgx.Tx 와 pgxpool.Pool 둘 다 만족).
type querier interface {
	QueryRow(ctx context.Context, sql string, args ...any) pgx.Row
	Query(ctx context.Context, sql string, args ...any) (pgx.Rows, error)
	Exec(ctx context.Context, sql string, args ...any) (pgconn.CommandTag, error)
}

const msgCols = `id, source, template_code, batch_id, dedupe_key, channels, to_email, to_slack_webhook_url, variables,
	missing_variables, email_subject, email_body, slack_text, tags, scheduled_for, status, created_at`

func scanMessage(row pgx.Row) (Message, error) {
	var m Message
	var channels, missing string
	var tags []byte
	err := row.Scan(&m.ID, &m.Source, &m.TemplateCode, &m.BatchID, &m.DedupeKey, &channels, &m.ToEmail, &m.ToSlackWebhookURL,
		&m.Variables, &missing, &m.EmailSubject, &m.EmailBody, &m.SlackText, &tags, &m.ScheduledFor, &m.Status, &m.CreatedAt)
	if err != nil {
		return m, err
	}
	m.Channels = splitCSV(channels)
	m.MissingVariables = splitCSV(missing)
	m.Tags = map[string]string{}
	if len(tags) > 0 {
		_ = json.Unmarshal(tags, &m.Tags)
	}
	return m, nil
}

func (r *Repo) Insert(ctx context.Context, q querier, m Message) (Message, error) {
	tags, _ := json.Marshal(m.Tags)
	if m.Tags == nil {
		tags = []byte("{}")
	}
	err := q.QueryRow(ctx, `
		insert into notification_messages (source, template_code, batch_id, dedupe_key, channels, to_email, to_slack_webhook_url, variables,
		  missing_variables, email_subject, email_body, slack_text, tags, scheduled_for, status, created_at, updated_at)
		values ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13::jsonb,$14,'PENDING',$15,$15) returning id, created_at`,
		m.Source, m.TemplateCode, m.BatchID, m.DedupeKey, strings.Join(m.Channels, ","), m.ToEmail, m.ToSlackWebhookURL, m.Variables,
		strings.Join(m.MissingVariables, ","), m.EmailSubject, m.EmailBody, m.SlackText, string(tags), m.ScheduledFor, db.Now()).Scan(&m.ID, &m.CreatedAt)
	if err != nil {
		return m, err
	}
	m.Status = Pending
	for _, ch := range m.Channels {
		if _, err := q.Exec(ctx, `insert into notification_deliveries (message_id, channel, status, attempts, updated_at) values ($1,$2,'PENDING',0,$3)`, m.ID, ch, db.Now()); err != nil {
			return m, err
		}
	}
	return m, nil
}

func (r *Repo) FindByDedupeKey(ctx context.Context, q querier, key string) (*Message, error) {
	m, err := scanMessage(q.QueryRow(ctx, `select `+msgCols+` from notification_messages where dedupe_key = $1`, key))
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	return &m, nil
}

func (r *Repo) FindByID(ctx context.Context, q querier, id int64) (*Message, error) {
	m, err := scanMessage(q.QueryRow(ctx, `select `+msgCols+` from notification_messages where id = $1`, id))
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	return &m, nil
}

// LockPending 은 트랜잭션 안에서 메시지를 잠근다. SKIP LOCKED 덕분에 서버가 여러 대여도 같은 메시지를 둘이 보내지 않는다.
func (r *Repo) LockPending(ctx context.Context, tx pgx.Tx, id int64) (*Message, error) {
	m, err := scanMessage(tx.QueryRow(ctx, `select `+msgCols+` from notification_messages where id = $1 and status = 'PENDING' for update skip locked`, id))
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	return &m, nil
}

// DuePendingIDs 는 때가 된 PENDING 메시지 id (오래된 순, 최대 limit).
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

// Search 는 꼬리표(tags)와 source 로 최근 limit 건. tags 는 JSON 포함 검색 (jsonb @>).
func (r *Repo) Search(ctx context.Context, tags map[string]string, source string, limit int) ([]Message, error) {
	tagJSON, _ := json.Marshal(tags)
	if tags == nil {
		tagJSON = []byte("{}")
	}
	rows, err := r.pool.Query(ctx, `select `+msgCols+` from notification_messages
		where tags @> $1::jsonb and ($2 = '' or source = $2) order by id desc limit $3`, string(tagJSON), source, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []Message{}
	for rows.Next() {
		m, err := scanMessage(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, m)
	}
	return out, rows.Err()
}

func (r *Repo) Deliveries(ctx context.Context, q querier, messageID int64) ([]Delivery, error) {
	rows, err := q.Query(ctx, `select id, message_id, channel, status, attempts, last_error, sent_at from notification_deliveries where message_id = $1 order by id`, messageID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []Delivery{}
	for rows.Next() {
		var d Delivery
		if err := rows.Scan(&d.ID, &d.MessageID, &d.Channel, &d.Status, &d.Attempts, &d.LastError, &d.SentAt); err != nil {
			return nil, err
		}
		out = append(out, d)
	}
	return out, rows.Err()
}

func (r *Repo) MarkDeliverySent(ctx context.Context, q querier, id int64, at time.Time) error {
	_, err := q.Exec(ctx, `update notification_deliveries set status='SENT', attempts=attempts+1, last_error=null, sent_at=$2, updated_at=$2 where id=$1`, id, at)
	return err
}

// MarkDeliveryFailed 는 attempts 를 올리고, 상한에 닿았을 때만 FAILED 로 (그 전엔 PENDING 으로 남아 재시도).
func (r *Repo) MarkDeliveryFailed(ctx context.Context, q querier, id int64, reason string, maxAttempts int) error {
	_, err := q.Exec(ctx, `update notification_deliveries set attempts=attempts+1, last_error=$2,
		status = case when attempts+1 >= $3 then 'FAILED' else status end, updated_at=$4 where id=$1`, id, cut(reason, 500), maxAttempts, db.Now())
	return err
}

func (r *Repo) SetStatus(ctx context.Context, q querier, id int64, s Status) error {
	_, err := q.Exec(ctx, `update notification_messages set status=$2, updated_at=$3 where id=$1`, id, s, db.Now())
	return err
}

// CancelPendingOfBatch 는 묶음 취소: 아직 안 나간 메시지만 취소한다.
func (r *Repo) CancelPendingOfBatch(ctx context.Context, batchID int64) (int64, error) {
	tag, err := r.pool.Exec(ctx, `update notification_messages set status='CANCELLED', updated_at=$2 where batch_id=$1 and status='PENDING'`, batchID, db.Now())
	return tag.RowsAffected(), err
}

func (r *Repo) CountByBatch(ctx context.Context, batchID int64) (map[Status]int64, error) {
	rows, err := r.pool.Query(ctx, `select status, count(*) from notification_messages where batch_id = $1 group by status`, batchID)
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

// MaskAddressesOlderThan 은 오래된 기록의 수신 주소를 가린다 (개인정보 보관 규칙). 반환: 가린 행 수.
//   - 이메일: 첫 글자 + *** + @도메인   (ab@x.com → a***@x.com)
//   - 슬랙 웹훅: 끝 6자만 남김
func (r *Repo) MaskAddressesOlderThan(ctx context.Context, before time.Time) (int64, error) {
	tag, err := r.pool.Exec(ctx, `update notification_messages set
		to_email = case when to_email is null then null else left(to_email, 1) || '***' || substring(to_email from position('@' in to_email)) end,
		to_slack_webhook_url = case when to_slack_webhook_url is null then null else '…' || right(to_slack_webhook_url, 6) end,
		address_masked = true, updated_at = $2
		where created_at < $1 and address_masked = false`, before, db.Now())
	return tag.RowsAffected(), err
}

func splitCSV(s string) []string {
	if s == "" {
		return []string{}
	}
	return strings.Split(s, ",")
}

func cut(s string, n int) string {
	if len(s) <= n {
		return s
	}
	return s[:n]
}
