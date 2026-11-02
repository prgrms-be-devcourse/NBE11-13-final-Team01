package message

import (
	"context"
	"encoding/json"
	"fmt"
	"log/slog"
	"regexp"
	"strings"

	"github.com/zipick/notification/internal/channel"
	"github.com/zipick/notification/internal/config"
	"github.com/zipick/notification/internal/db"
	"github.com/zipick/notification/internal/httpx"
	"github.com/zipick/notification/internal/template"
)

// Service 는 요청 검사 → 문구 채우기 → 적기 → 보내기. 판단 로직은 없다.
type Service struct {
	repo      *Repo
	templates *template.Store
	channels  map[string]channel.Channel // 이름 → 채널 (켜진 것만)
	cfg       config.Config
}

func NewService(repo *Repo, templates *template.Store, channels []channel.Channel, cfg config.Config) *Service {
	return &Service{repo: repo, templates: templates, channels: channel.ByName(channels), cfg: cfg}
}

// Available 은 채널 이름 → 켜져 있는지 (GET /internal/v1/channels).
func (s *Service) Available() map[string]bool {
	out := map[string]bool{"email": false, "slack": false}
	for name := range s.channels {
		out[name] = true
	}
	return out
}

// Recipient 는 요청의 to.
type Recipient struct {
	Email           *string `json:"email"`
	SlackWebhookURL *string `json:"slackWebhookUrl"`
}

// Request 는 스펙 v1 의 알림 요청. 문구는 모르고, 템플릿 코드와 변수만 안다. 큐로 오든 HTTP 로 오든 같다.
type Request struct {
	Source       string            `json:"source"`       // 요청한 서비스 (main, collector, auth …)
	TemplateCode string            `json:"templateCode"` // templates/*.yaml 의 code
	Channels     []string          `json:"channels"`     // email, slack
	To           Recipient         `json:"to"`           // 고른 채널의 주소
	Variables    map[string]any    `json:"variables"`
	DedupeKey    *string           `json:"dedupeKey"`   // 같은 키는 한 번만 적힌다
	ScheduledAt  *db.LocalDateTime `json:"scheduledAt"` // 비우면 바로
	Tags         map[string]string `json:"tags"`        // 알림센터가 해석하지 않는 꼬리표 (조회용)
	Delivery     string            `json:"delivery"`    // "" | "now": now 면 기다리지 않고 바로 보내고 채널별 결과를 돌려준다 (재시도 없음)
}

var (
	sourcePattern = regexp.MustCompile(`^[a-z][a-z0-9-]{0,39}$`)
	emailPattern  = regexp.MustCompile(`^[^@\s]+@[^@\s]+\.[^@\s]+$`)
)

// Validate 는 요청 단계에서 거르는 것. 잘못된 요청은 적히지 않고 바로 400 이라 요청한 쪽이 실수를 금방 안다.
func (s *Service) Validate(r Request) (template.Template, error) {
	var t template.Template
	switch {
	case !sourcePattern.MatchString(r.Source):
		return t, httpx.Fail(httpx.InvalidInput, "source 는 소문자·숫자·- 로 1~40자여야 합니다 (예 main, collector)")
	case len(r.Channels) == 0:
		return t, httpx.Fail(httpx.InvalidInput, "channels 는 하나 이상 골라야 합니다 (email, slack)")
	case r.DedupeKey != nil && (len(*r.DedupeKey) == 0 || len(*r.DedupeKey) > 120):
		return t, httpx.Fail(httpx.InvalidInput, "dedupeKey 는 1~120자여야 합니다")
	case r.Delivery != "" && r.Delivery != "now":
		return t, httpx.Fail(httpx.InvalidInput, "delivery 는 비우거나 now 여야 합니다")
	case len(r.Tags) > 20:
		return t, httpx.Fail(httpx.InvalidInput, "tags 는 20개 이하여야 합니다")
	}
	t, ok := s.templates.Get(r.TemplateCode)
	if !ok {
		return t, httpx.Fail(httpx.TemplateNotFound, "템플릿이 없습니다: "+r.TemplateCode)
	}
	seen := map[string]bool{}
	for _, ch := range r.Channels {
		if seen[ch] {
			return t, httpx.Fail(httpx.InvalidInput, "channels 에 같은 채널이 두 번 있습니다: "+ch)
		}
		seen[ch] = true
		c, on := s.channels[ch]
		if ch != "email" && ch != "slack" {
			return t, httpx.Fail(httpx.InvalidInput, "모르는 채널입니다: "+ch)
		}
		if !on {
			return t, httpx.Fail(httpx.ChannelUnavailable, "이 서버에 "+ch+" 채널이 꺼져 있습니다")
		}
		if !t.HasChannel(ch) {
			return t, httpx.Fail(httpx.InvalidInput, "템플릿 "+t.Code+" 에 "+ch+" 문구가 없습니다")
		}
		if !c.HasAddress(channel.Recipient{Email: r.To.Email, SlackWebhookURL: r.To.SlackWebhookURL}) {
			return t, httpx.Fail(httpx.NoRecipient, ch+" 채널을 골랐는데 to 에 그 주소가 없습니다")
		}
	}
	if r.To.Email != nil && (len(*r.To.Email) > 255 || !emailPattern.MatchString(*r.To.Email)) {
		return t, httpx.Fail(httpx.InvalidInput, "to.email 형식이 올바르지 않습니다")
	}
	if r.To.SlackWebhookURL != nil && (len(*r.To.SlackWebhookURL) > 500 || !strings.HasPrefix(*r.To.SlackWebhookURL, channel.SlackWebhookPrefix)) {
		return t, httpx.Fail(httpx.InvalidInput, "to.slackWebhookUrl 은 https://hooks.slack.com/ 으로 시작해야 합니다")
	}
	return t, nil
}

// Build 는 검사가 끝난 요청으로 아직 저장하지 않은 Message 를 만든다 (묶음 요청도 이 함수를 쓴다).
func Build(t template.Template, r Request, batchID *int64) Message {
	vars := r.Variables
	if vars == nil {
		vars = map[string]any{}
	}
	rendered, missing := t.RenderAll(r.Channels, vars)
	when := db.Now()
	if r.ScheduledAt != nil && !r.ScheduledAt.IsZero() {
		when = r.ScheduledAt.Time
	}
	varsJSON, _ := json.Marshal(vars)
	m := Message{Source: r.Source, TemplateCode: t.Code, BatchID: batchID, DedupeKey: r.DedupeKey, Channels: r.Channels,
		ToEmail: r.To.Email, ToSlackWebhookURL: r.To.SlackWebhookURL, Variables: string(varsJSON), MissingVariables: missing,
		Tags: r.Tags, ScheduledFor: when}
	if e, ok := rendered["email"]; ok {
		subj, body := cutRunes(e.Subject, 300), e.Body
		m.EmailSubject, m.EmailBody = &subj, &body
	}
	if sl, ok := rendered["slack"]; ok {
		text := sl.Body
		m.SlackText = &text
	}
	return m
}

// Enqueue 는 요청 하나를 적는다. 같은 dedupeKey 가 이미 있으면 기존 건을 duplicate=true 로 돌려준다 (멱등).
// delivery=now 면 적은 뒤 바로 보내고 채널별 결과를 돌려준다.
func (s *Service) Enqueue(ctx context.Context, r Request) (Response, error) {
	t, err := s.Validate(r)
	if err != nil {
		return Response{}, err
	}
	if r.DedupeKey != nil {
		existing, err := s.repo.FindByDedupeKey(ctx, s.repo.pool, *r.DedupeKey)
		if err != nil {
			return Response{}, err
		}
		if existing != nil {
			return s.response(ctx, *existing, true)
		}
	}
	m, err := s.repo.Insert(ctx, s.repo.pool, Build(t, r, nil))
	if err != nil {
		return Response{}, err
	}
	if len(m.MissingVariables) > 0 {
		slog.Warn("템플릿 변수 비어 있음", "source", r.Source, "template", t.Code, "missing", m.MissingVariables)
	}
	if r.Delivery == "now" {
		// 테스트 발송 같은 용도: 재시도 없이 한 번 보내고 결과를 바로 알려 준다
		if err := s.dispatchOne(ctx, m.ID, 1); err != nil {
			return Response{}, err
		}
		return s.Get(ctx, m.ID) // 발송 결과가 반영된 상태로
	}
	return s.response(ctx, m, false)
}

// Get 은 메시지 하나와 채널별 상태.
func (s *Service) Get(ctx context.Context, id int64) (Response, error) {
	m, err := s.repo.FindByID(ctx, s.repo.pool, id)
	if err != nil {
		return Response{}, err
	}
	if m == nil {
		return Response{}, httpx.Fail(httpx.MessageNotFound, fmt.Sprintf("메시지가 없습니다: %d", id))
	}
	return s.response(ctx, *m, false)
}

// Search 는 꼬리표·source 로 최근 50건.
func (s *Service) Search(ctx context.Context, tags map[string]string, source string) ([]Response, error) {
	list, err := s.repo.Search(ctx, tags, source, 50)
	if err != nil {
		return nil, err
	}
	out := make([]Response, 0, len(list))
	for _, m := range list {
		res, err := s.response(ctx, m, false)
		if err != nil {
			return nil, err
		}
		out = append(out, res)
	}
	return out, nil
}

// DispatchPending 은 때가 된 PENDING 메시지를 보낸다. 건마다 트랜잭션. 반환: 이번에 SENT 가 된 메시지 수.
func (s *Service) DispatchPending(ctx context.Context) (int, error) {
	ids, err := s.repo.DuePendingIDs(ctx, db.Now(), 200)
	if err != nil {
		return 0, err
	}
	sent := 0
	for _, id := range ids {
		if err := s.dispatchOne(ctx, id, s.cfg.MaxAttempts); err != nil {
			slog.Error("발송 처리 실패", "message", id, "err", err)
			continue
		}
		if m, _ := s.repo.FindByID(ctx, s.repo.pool, id); m != nil && m.Status == Sent {
			sent++
		}
	}
	return sent, nil
}

// dispatchOne 은 메시지 하나를 잠그고(SKIP LOCKED) 아직 안 보낸 채널에만 보낸다. 끝나면 메시지 상태를 다시 정한다.
func (s *Service) dispatchOne(ctx context.Context, id int64, maxAttempts int) error {
	tx, err := s.repo.pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx) // Commit 뒤의 Rollback 은 아무 일도 하지 않는다

	m, err := s.repo.LockPending(ctx, tx, id)
	if err != nil || m == nil {
		return err // 다른 서버가 잡았거나 이미 처리됨
	}
	deliveries, err := s.repo.Deliveries(ctx, tx, m.ID)
	if err != nil {
		return err
	}
	to := channel.Recipient{Email: m.ToEmail, SlackWebhookURL: m.ToSlackWebhookURL}
	for _, d := range deliveries {
		if d.Status != DPending {
			continue // 이미 성공했거나 포기한 채널로는 다시 보내지 않는다
		}
		c, on := s.channels[d.Channel]
		if !on {
			_ = s.repo.MarkDeliveryFailed(ctx, tx, d.ID, "채널이 꺼져 있음", 1)
			continue
		}
		if err := c.Send(ctx, to, contentFor(*m, d.Channel)); err != nil {
			slog.Warn("발송 실패", "message", m.ID, "channel", d.Channel, "err", err)
			_ = s.repo.MarkDeliveryFailed(ctx, tx, d.ID, err.Error(), maxAttempts)
			continue
		}
		_ = s.repo.MarkDeliverySent(ctx, tx, d.ID, db.Now())
	}
	after, err := s.repo.Deliveries(ctx, tx, m.ID)
	if err != nil {
		return err
	}
	if err := s.repo.SetStatus(ctx, tx, m.ID, Summarize(after)); err != nil {
		return err
	}
	return tx.Commit(ctx)
}

// Summarize 는 채널별 결과로 메시지 상태를 정한다.
func Summarize(ds []Delivery) Status {
	sent, failed, pending := 0, 0, 0
	for _, d := range ds {
		switch d.Status {
		case DSent:
			sent++
		case DFailed:
			failed++
		default:
			pending++
		}
	}
	switch {
	case pending > 0:
		return Pending
	case failed == 0:
		return Sent
	case sent == 0:
		return Failed
	}
	return Partial
}

func contentFor(m Message, ch string) channel.Content {
	switch ch {
	case "email":
		return channel.Content{Subject: deref(m.EmailSubject), Body: deref(m.EmailBody)}
	default:
		return channel.Content{Body: deref(m.SlackText)}
	}
}

// MaskOldAddresses 는 보관 기간이 지난 기록의 주소를 가린다 (하루 한 번).
func (s *Service) MaskOldAddresses(ctx context.Context) (int64, error) {
	return s.repo.MaskAddressesOlderThan(ctx, db.Now().AddDate(0, 0, -s.cfg.RetentionDays))
}

func (s *Service) response(ctx context.Context, m Message, duplicate bool) (Response, error) {
	ds, err := s.repo.Deliveries(ctx, s.repo.pool, m.ID)
	if err != nil {
		return Response{}, err
	}
	return ToResponse(m, ds, duplicate), nil
}

func deref(p *string) string {
	if p == nil {
		return ""
	}
	return *p
}

// cutRunes 는 글자 수 기준으로 자른다 (한글은 바이트가 3개라 len() 으로 자르면 깨진다).
func cutRunes(s string, n int) string {
	r := []rune(s)
	if len(r) <= n {
		return s
	}
	return string(r[:n])
}
