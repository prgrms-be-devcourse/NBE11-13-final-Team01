package message

import (
	"context"
	"encoding/json"
	"fmt"
	"log/slog"
	"strings"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/zipick/notification/internal/channel"
	"github.com/zipick/notification/internal/config"
	"github.com/zipick/notification/internal/db"
	"github.com/zipick/notification/internal/httpx"
	"github.com/zipick/notification/internal/subscription"
	"github.com/zipick/notification/internal/template"
)

// Service 는 요청 → 템플릿 렌더링 → 아웃박스 적기 → (스케줄러) 보내기.
// 카테고리별 규칙:
//
//	TRANSACTIONAL: 동의와 무관. 구독이 없으면 요청의 email 로.
//	INFORMATIONAL: 구독 enabled 인 사용자만. 아니면 SKIPPED.
//	MARKETING    : 광고 수신 동의자만. 제목 앞 "(광고)", 21~08시는 다음 08시로 미룸.
type Service struct {
	repo      *Repo
	subs      *subscription.Repo
	templates *template.Repo
	channels  []channel.Channel
	cfg       config.Config
	quiet     quietHours
}

func NewService(repo *Repo, subs *subscription.Repo, templates *template.Repo, channels []channel.Channel, cfg config.Config) (*Service, error) {
	q, err := parseQuietHours(cfg.QuietStart, cfg.QuietEnd)
	if err != nil {
		return nil, err
	}
	return &Service{repo: repo, subs: subs, templates: templates, channels: channels, cfg: cfg, quiet: q}, nil
}

// EmailAvailable 은 이메일 채널이 켜져 있는지.
func (s *Service) EmailAvailable() bool { return channel.HasEmail(s.channels) }

// Request 는 다른 서버가 보내는 "알림 요청". 문구는 모르고, 템플릿 코드와 변수만 안다.
//   - UserID       : 구독(수신 주소·동의)을 찾는 키. 가입 인증처럼 계정이 없으면 비우고 Email 을 직접 준다
//   - DedupeKey    : 같은 알림을 두 번 적지 않기 위한 키 (예 DEADLINE:{user}:{notice}:{d}). 없으면 매번 적는다
//   - ScheduledFor : 비우면 바로. 넣으면 그 시각 이후 첫 주기에 보낸다
type Request struct {
	TemplateCode    string            `json:"templateCode"`
	UserID          *int64            `json:"userId"`
	Email           *string           `json:"email"`
	SlackWebhookURL *string           `json:"slackWebhookUrl"`
	Variables       map[string]any    `json:"variables"`
	DedupeKey       *string           `json:"dedupeKey"`
	ScheduledFor    *db.LocalDateTime `json:"scheduledFor"`
}

func (r Request) validate() error {
	switch {
	case strings.TrimSpace(r.TemplateCode) == "" || len(r.TemplateCode) > 60:
		return httpx.Fail(httpx.InvalidInput, "templateCode 는 1~60자여야 합니다.")
	case r.UserID == nil && r.Email == nil && r.SlackWebhookURL == nil:
		return httpx.Fail(httpx.NoRecipient, "userId 또는 email/slackWebhookUrl 중 하나는 있어야 합니다.")
	case r.DedupeKey != nil && len(*r.DedupeKey) > 120:
		return httpx.Fail(httpx.InvalidInput, "dedupeKey 는 120자 이하여야 합니다.")
	}
	return nil
}

// Enqueue 는 아웃박스에 적는다. 같은 dedupeKey 가 이미 있으면 적지 않고 기존 건을 duplicate=true 로 돌려준다 (멱등).
func (s *Service) Enqueue(ctx context.Context, r Request) (Response, error) {
	if err := r.validate(); err != nil {
		return Response{}, err
	}
	if r.DedupeKey != nil {
		if existing, err := s.repo.FindByDedupeKey(ctx, s.repo.pool, *r.DedupeKey); err != nil {
			return Response{}, err
		} else if existing != nil {
			return ToResponse(*existing, true), nil
		}
	}
	t, err := s.templates.FindByCode(ctx, r.TemplateCode)
	if err != nil {
		return Response{}, err
	}
	if !t.Enabled {
		return Response{}, httpx.Fail(httpx.InvalidInput, "꺼져 있는 템플릿입니다: "+t.Code)
	}
	var when time.Time
	if r.ScheduledFor != nil {
		when = r.ScheduledFor.Time
	}
	m := s.Build(t, r.UserID, r.Email, r.SlackWebhookURL, r.Variables, r.DedupeKey, when, nil)
	saved, err := s.repo.Insert(ctx, s.repo.pool, m)
	if err != nil {
		return Response{}, err
	}
	return ToResponse(saved, false), nil
}

// Build 는 템플릿을 채워 아직 저장하지 않은 Message 를 만든다. 캠페인도 이 함수를 써서 규칙(광고 표기·야간 금지)이 한 곳에만 있다.
func (s *Service) Build(t template.Template, userID *int64, email, slack *string, vars map[string]any,
	dedupeKey *string, scheduledFor time.Time, campaignID *int64) Message {
	v := map[string]any{"appUrl": s.cfg.AppURL}
	for k, val := range vars {
		v[k] = val
	}
	title, body := template.Render(t.TitleTemplate, v), template.Render(t.BodyTemplate, v)
	var url *string
	missing := append(title.Missing, body.Missing...)
	if t.URLTemplate != nil {
		u := template.Render(*t.URLTemplate, v)
		missing = append(missing, u.Missing...)
		if strings.TrimSpace(u.Text) != "" {
			url = &u.Text
		}
	}
	if len(missing) > 0 {
		slog.Warn("템플릿 변수 비어 있음", "template", t.Code, "missing", missing)
	}
	when := scheduledFor
	if when.IsZero() {
		when = db.Now()
	}
	titleText := title.Text
	if t.Category == template.Marketing {
		when = s.quiet.shift(when)
		if !strings.HasPrefix(titleText, "(광고)") {
			titleText = "(광고) " + titleText
		}
	}
	varsJSON, _ := json.Marshal(v)
	vs := string(varsJSON)
	return Message{UserID: userID, TemplateCode: t.Code, Category: t.Category, CampaignID: campaignID, DedupeKey: dedupeKey,
		Variables: &vs, ToEmail: email, ToSlackWebhookURL: slack, Title: cutRunes(titleText, 300), Body: body.Text, URL: url, ScheduledFor: when}
}

// SendTest 는 설정 화면의 "테스트 발송": 바로 적고 바로 보낸다. 재시도하지 않고 결과를 즉시 보여 준다.
func (s *Service) SendTest(ctx context.Context, userID int64) (Response, error) {
	sub, err := s.subs.FindByUser(ctx, userID)
	if err != nil {
		return Response{}, err
	}
	if sub == nil {
		return Response{}, httpx.Fail(httpx.SubscriptionNotFound, "알림 설정을 먼저 저장해 주세요.")
	}
	to := channel.Recipient{Email: sub.Email, SlackWebhookURL: sub.SlackWebhookURL}
	if !s.anySupports(to) {
		return Response{}, httpx.Fail(httpx.NoChannel, "보낼 채널이 없습니다. 슬랙 웹훅 주소나 이메일을 넣어 주세요.")
	}
	t, err := s.templates.FindByCode(ctx, "TEST")
	if err != nil {
		return Response{}, err
	}
	days := make([]string, 0)
	for _, d := range sub.DeadlineDayList() {
		days = append(days, fmt.Sprint(d))
	}
	key := fmt.Sprintf("TEST:%d:%s", userID, db.Now().Format("2006-01-02T15:04:05.000"))
	m, err := s.repo.Insert(ctx, s.repo.pool, s.Build(t, &userID, nil, nil, map[string]any{"deadlineDays": strings.Join(days, "·")}, &key, time.Time{}, nil))
	if err != nil {
		return Response{}, err
	}
	s.deliver(ctx, s.repo.pool, m, to, 1)
	saved, err := s.repo.FindByID(ctx, s.repo.pool, m.ID)
	if err != nil {
		return Response{}, err
	}
	return ToResponse(saved, false), nil
}

func (s *Service) History(ctx context.Context, userID int64) ([]Response, error) {
	list, err := s.repo.History(ctx, userID)
	if err != nil {
		return nil, err
	}
	out := make([]Response, 0, len(list))
	for _, m := range list {
		out = append(out, ToResponse(m, false))
	}
	return out, nil
}

// DispatchPending 은 때가 된 PENDING 메시지를 보낸다. 건마다 트랜잭션이라 하나가 실패해도 나머지는 진행된다. 반환: 보낸 건수.
func (s *Service) DispatchPending(ctx context.Context) (int, error) {
	ids, err := s.repo.DuePendingIDs(ctx, db.Now(), 200)
	if err != nil {
		return 0, err
	}
	sent := 0
	for _, id := range ids {
		ok, err := s.dispatchOne(ctx, id)
		if err != nil {
			slog.Error("발송 처리 실패", "message", id, "err", err)
			continue
		}
		if ok {
			sent++
		}
	}
	return sent, nil
}

// dispatchOne 은 메시지 하나를 트랜잭션 안에서 잠그고(SKIP LOCKED) 보낸다.
func (s *Service) dispatchOne(ctx context.Context, id int64) (bool, error) {
	tx, err := s.repo.pool.Begin(ctx)
	if err != nil {
		return false, err
	}
	defer tx.Rollback(ctx) // Commit 이 끝났으면 Rollback 은 아무 일도 하지 않는다 (Java 의 finally 와 같음)

	m, err := s.repo.LockPending(ctx, tx, id)
	if err != nil || m == nil {
		return false, err // 다른 서버가 잡았거나 이미 처리됨
	}
	var sub *subscription.Subscription
	if m.UserID != nil {
		if sub, err = s.subs.FindByUser(ctx, *m.UserID); err != nil {
			return false, err
		}
	}
	skip := ""
	switch m.Category {
	case template.Informational:
		if sub == nil || !sub.Enabled {
			skip = "정보성 알림이 꺼져 있음"
		}
	case template.Marketing:
		if sub == nil || !sub.MarketingConsent {
			skip = "광고 수신 동의 없음"
		}
	}
	if skip != "" {
		return false, finish(ctx, tx, s.repo.MarkSkipped(ctx, tx, m.ID, skip))
	}
	// 요청이 직접 준 주소가 우선, 없으면 구독의 주소
	to := channel.Recipient{Email: m.ToEmail, SlackWebhookURL: m.ToSlackWebhookURL}
	if sub != nil {
		if to.Email == nil {
			to.Email = sub.Email
		}
		if to.SlackWebhookURL == nil {
			to.SlackWebhookURL = sub.SlackWebhookURL
		}
	}
	if to.IsEmpty() {
		return false, finish(ctx, tx, s.repo.MarkSkipped(ctx, tx, m.ID, "받을 주소가 없음"))
	}
	ok := s.deliver(ctx, tx, *m, to, s.cfg.MaxAttempts)
	return ok, tx.Commit(ctx)
}

func finish(ctx context.Context, tx pgx.Tx, err error) error {
	if err != nil {
		return err
	}
	return tx.Commit(ctx)
}

// deliver 는 받는 쪽이 지원하는 채널 전부로 보내고 결과를 기록한다. 하나라도 성공하면 SENT.
func (s *Service) deliver(ctx context.Context, q querier, m Message, to channel.Recipient, maxAttempts int) bool {
	var ok, errs []string
	for _, c := range s.channels {
		if !c.Supports(to) {
			continue
		}
		if err := c.Send(ctx, to, channel.Message{Title: m.Title, Body: m.Body, URL: m.URL}); err != nil {
			slog.Warn("발송 실패", "message", m.ID, "channel", c.Name(), "err", err)
			errs = append(errs, c.Name()+": "+err.Error())
			continue
		}
		ok = append(ok, c.Name())
	}
	if len(ok) > 0 {
		_ = s.repo.MarkSent(ctx, q, m.ID, strings.Join(ok, ","), db.Now())
		return true
	}
	reason := "보낼 채널이 없음 (이메일 provider 설정 확인)"
	if len(errs) > 0 {
		reason = strings.Join(errs, " / ")
	}
	_ = s.repo.MarkFailed(ctx, q, m.ID, reason, maxAttempts)
	return false
}

func (s *Service) anySupports(to channel.Recipient) bool {
	for _, c := range s.channels {
		if c.Supports(to) {
			return true
		}
	}
	return false
}

// cutRunes 는 글자 수 기준으로 자른다 (한글은 바이트가 3개라 len() 으로 자르면 깨진다).
func cutRunes(s string, n int) string {
	r := []rune(s)
	if len(r) <= n {
		return s
	}
	return string(r[:n])
}
