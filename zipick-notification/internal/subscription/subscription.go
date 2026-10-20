// Package subscription 은 사용자별 수신 정보와 동의를 다룬다.
// 사용자 id 는 메인 서버의 것이다 (알림센터는 계정을 모른다).
//   - Enabled          : 정보성 알림(마감·새 공고)을 받을지
//   - MarketingConsent : 광고성 정보 수신 동의. 동의 시각을 남긴다 (정보통신망법상 동의 기록 보관)
//   - DeadlineDays, NewMatchEnabled : 메인 서버의 스케줄러가 무엇을 적을지 정할 때 읽어 간다
package subscription

import (
	"context"
	"errors"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/zipick/notification/internal/db"
	"github.com/zipick/notification/internal/httpx"
)

const (
	SlackWebhookPrefix  = "https://hooks.slack.com/"
	DefaultDeadlineDays = "3,1,0"
)

// Subscription 은 subscriptions 한 행.
type Subscription struct {
	UserID               int64
	Enabled              bool
	SlackWebhookURL      *string
	Email                *string
	MarketingConsent     bool
	MarketingConsentedAt *time.Time
	DeadlineDays         string // "3,1,0"
	NewMatchEnabled      bool
}

func (s Subscription) HasSlack() bool { return s.SlackWebhookURL != nil }
func (s Subscription) HasEmail() bool { return s.Email != nil }

// DeadlineDayList 는 "1, 3,0,3" → [3 1 0] (중복 제거, 내림차순).
func (s Subscription) DeadlineDayList() []int {
	seen := map[int]bool{}
	var out []int
	for _, p := range strings.Split(s.DeadlineDays, ",") {
		n, err := strconv.Atoi(strings.TrimSpace(p))
		if err != nil || seen[n] {
			continue
		}
		seen[n] = true
		out = append(out, n)
	}
	sort.Sort(sort.Reverse(sort.IntSlice(out)))
	if out == nil {
		out = []int{}
	}
	return out
}

// Request 는 PUT 본문. SlackWebhookURL / Email 을 보내지 않으면(nil) 기존 값 유지, "" 를 보내면 지운다.
// Go 에서 "안 보냄"과 "빈 값"을 구분하려면 포인터를 쓴다 (Java 의 null 과 "" 구분과 같다).
type Request struct {
	Enabled          bool    `json:"enabled"`
	SlackWebhookURL  *string `json:"slackWebhookUrl"`
	Email            *string `json:"email"`
	MarketingConsent *bool   `json:"marketingConsent"`
	DeadlineDays     *string `json:"deadlineDays"`
	NewMatchEnabled  bool    `json:"newMatchEnabled"`
}

var (
	emailPattern        = regexp.MustCompile(`^[^@\s]+@[^@\s]+\.[^@\s]+$`)
	deadlineDaysPattern = regexp.MustCompile(`^\s*$|^\d+(\s*,\s*\d+)*$`)
)

func (r Request) Validate() error {
	if r.SlackWebhookURL != nil {
		u := strings.TrimSpace(*r.SlackWebhookURL)
		if len(u) > 500 {
			return httpx.Fail(httpx.InvalidInput, "슬랙 웹훅 주소는 500자 이하여야 합니다.")
		}
		if u != "" && !strings.HasPrefix(u, SlackWebhookPrefix) {
			return httpx.Fail(httpx.InvalidInput, "슬랙 웹훅 주소는 https://hooks.slack.com/ 으로 시작해야 합니다.")
		}
	}
	if r.Email != nil {
		e := strings.TrimSpace(*r.Email)
		if e != "" && (len(e) > 255 || !emailPattern.MatchString(e)) {
			return httpx.Fail(httpx.InvalidInput, "이메일 형식이 올바르지 않습니다.")
		}
	}
	if r.DeadlineDays != nil && !deadlineDaysPattern.MatchString(*r.DeadlineDays) {
		return httpx.Fail(httpx.InvalidInput, "마감 알림 일수는 숫자를 쉼표로 구분해 적어 주세요 (예: 3,1,0)")
	}
	return nil
}

// Response 는 메인 서버의 NotificationSettingResponse 와 같은 모양. 웹훅 URL 은 비밀값이라 끝 6자만 보여 준다.
type Response struct {
	UserID               int64             `json:"userId"`
	Enabled              bool              `json:"enabled"`
	SlackConfigured      bool              `json:"slackConfigured"`
	SlackWebhookMasked   *string           `json:"slackWebhookMasked"`
	Email                *string           `json:"email"`
	MarketingConsent     bool              `json:"marketingConsent"`
	MarketingConsentedAt *db.LocalDateTime `json:"marketingConsentedAt"`
	DeadlineDays         []int             `json:"deadlineDays"`
	NewMatchEnabled      bool              `json:"newMatchEnabled"`
	EmailAvailable       bool              `json:"emailAvailable"`
}

func ToResponse(s Subscription, emailAvailable bool) Response {
	var masked *string
	if s.SlackWebhookURL != nil {
		u := *s.SlackWebhookURL
		m := "…" + u[max(0, len(u)-6):]
		masked = &m
	}
	return Response{UserID: s.UserID, Enabled: s.Enabled, SlackConfigured: s.HasSlack(), SlackWebhookMasked: masked,
		Email: s.Email, MarketingConsent: s.MarketingConsent, MarketingConsentedAt: db.FromDBPtr(s.MarketingConsentedAt),
		DeadlineDays: s.DeadlineDayList(), NewMatchEnabled: s.NewMatchEnabled, EmailAvailable: emailAvailable}
}

// EmptyResponse 는 아직 설정이 없는 사용자에게 보여 줄 기본값.
func EmptyResponse(userID int64, emailAvailable bool) Response {
	return Response{UserID: userID, DeadlineDays: []int{3, 1, 0}, NewMatchEnabled: true, EmailAvailable: emailAvailable}
}

// Repo 는 subscriptions 조회·저장.
type Repo struct{ pool *pgxpool.Pool }

func NewRepo(pool *pgxpool.Pool) *Repo { return &Repo{pool: pool} }

const cols = `user_id, enabled, slack_webhook_url, email, marketing_consent, marketing_consented_at, deadline_days, new_match_enabled`

func scan(row pgx.Row) (Subscription, error) {
	var s Subscription
	err := row.Scan(&s.UserID, &s.Enabled, &s.SlackWebhookURL, &s.Email, &s.MarketingConsent, &s.MarketingConsentedAt, &s.DeadlineDays, &s.NewMatchEnabled)
	return s, err
}

// FindByUser 는 없으면 (nil, nil). "없음"은 오류가 아니라 정상 상황이라 nil 포인터로 표현한다.
func (r *Repo) FindByUser(ctx context.Context, userID int64) (*Subscription, error) {
	s, err := scan(r.pool.QueryRow(ctx, `select `+cols+` from subscriptions where user_id = $1`, userID))
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	return &s, nil
}

func (r *Repo) query(ctx context.Context, where string, args ...any) ([]Subscription, error) {
	rows, err := r.pool.Query(ctx, `select `+cols+` from subscriptions `+where+` order by user_id`, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Subscription
	for rows.Next() {
		s, err := scan(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, s)
	}
	return out, rows.Err()
}

func (r *Repo) FindEnabled(ctx context.Context) ([]Subscription, error) {
	return r.query(ctx, `where enabled`)
}
func (r *Repo) FindMarketingConsented(ctx context.Context) ([]Subscription, error) {
	return r.query(ctx, `where marketing_consent`)
}
func (r *Repo) FindByUserIDs(ctx context.Context, ids []int64) ([]Subscription, error) {
	return r.query(ctx, `where user_id = any($1)`, ids)
}

// Save 는 요청을 기존 행에 합쳐(없으면 새로) 저장하고 결과를 돌려준다.
// nil 은 "그대로", "" 는 "지우기" 규칙을 여기서 적용한다. 광고 동의가 바뀔 때만 동의 시각을 갱신한다.
func (r *Repo) Save(ctx context.Context, userID int64, req Request) (Subscription, error) {
	cur, err := r.FindByUser(ctx, userID)
	if err != nil {
		return Subscription{}, err
	}
	s := Subscription{UserID: userID, DeadlineDays: DefaultDeadlineDays, NewMatchEnabled: true}
	if cur != nil {
		s = *cur
	}
	s.Enabled = req.Enabled
	s.NewMatchEnabled = req.NewMatchEnabled
	if req.SlackWebhookURL != nil {
		s.SlackWebhookURL = blankToNil(*req.SlackWebhookURL)
	}
	if req.Email != nil {
		s.Email = blankToNil(*req.Email)
	}
	if req.MarketingConsent != nil && *req.MarketingConsent != s.MarketingConsent {
		s.MarketingConsent = *req.MarketingConsent
		if s.MarketingConsent {
			now := db.Now()
			s.MarketingConsentedAt = &now
		} else {
			s.MarketingConsentedAt = nil
		}
	}
	if req.DeadlineDays != nil && strings.TrimSpace(*req.DeadlineDays) != "" {
		s.DeadlineDays = strings.ReplaceAll(*req.DeadlineDays, " ", "")
	}
	_, err = r.pool.Exec(ctx, `
		insert into subscriptions (user_id, enabled, slack_webhook_url, email, marketing_consent, marketing_consented_at, deadline_days, new_match_enabled, created_at, updated_at)
		values ($1, $2, $3, $4, $5, $6, $7, $8, $9, $9)
		on conflict (user_id) do update set
		  enabled = excluded.enabled, slack_webhook_url = excluded.slack_webhook_url, email = excluded.email,
		  marketing_consent = excluded.marketing_consent, marketing_consented_at = excluded.marketing_consented_at,
		  deadline_days = excluded.deadline_days, new_match_enabled = excluded.new_match_enabled, updated_at = excluded.updated_at`,
		s.UserID, s.Enabled, s.SlackWebhookURL, s.Email, s.MarketingConsent, s.MarketingConsentedAt, s.DeadlineDays, s.NewMatchEnabled, db.Now())
	if err != nil {
		return Subscription{}, err
	}
	saved, err := r.FindByUser(ctx, userID)
	if err != nil {
		return Subscription{}, err
	}
	return *saved, nil
}

func blankToNil(s string) *string {
	s = strings.TrimSpace(s)
	if s == "" {
		return nil
	}
	return &s
}
