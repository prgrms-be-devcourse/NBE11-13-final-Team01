package message

import (
	"strings"
	"testing"
	"time"

	"github.com/zipick/notification/internal/channel"
	"github.com/zipick/notification/internal/config"
	"github.com/zipick/notification/internal/db"
	"github.com/zipick/notification/internal/template"
)

// DB 없이 만들 수 있는 부분(템플릿 적용 규칙, 요청 검증, 응답 변환)만 테스트한다.
func newService(t *testing.T) *Service {
	t.Helper()
	s, err := NewService(nil, nil, nil, []channel.Channel{channel.NewSlack()},
		config.Config{AppURL: "https://zipick.kr", QuietStart: "21:00", QuietEnd: "08:00", MaxAttempts: 3})
	if err != nil {
		t.Fatal(err)
	}
	return s
}

func ptr[T any](v T) *T { return &v }

func tpl(cat template.Category, title, body string, url *string) template.Template {
	return template.Template{Code: "T", Category: cat, TitleTemplate: title, BodyTemplate: body, URLTemplate: url, Enabled: true}
}

func TestNewService_야간_시간_형식이_틀리면_실패(t *testing.T) {
	if _, err := NewService(nil, nil, nil, nil, config.Config{QuietStart: "9pm", QuietEnd: "08:00"}); err == nil {
		t.Fatal("잘못된 형식을 통과시킴")
	}
}

func TestBuild_변수를_채우고_appUrl_을_항상_넣는다(t *testing.T) {
	s := newService(t)
	at := time.Date(2026, 10, 20, 14, 0, 0, 0, db.Seoul)
	m := s.Build(tpl(template.Informational, "마감 {{dDayLabel}}", "{{noticeTitle}} 확인", ptr("{{appUrl}}/calendar")),
		ptr[int64](1), nil, nil, map[string]any{"dDayLabel": "D-3", "noticeTitle": "행복주택"}, ptr("K1"), at, nil)

	if m.Title != "마감 D-3" || m.Body != "행복주택 확인" || *m.URL != "https://zipick.kr/calendar" {
		t.Fatalf("%+v", m)
	}
	if !m.ScheduledFor.Equal(at) || *m.DedupeKey != "K1" || !strings.Contains(*m.Variables, `"appUrl":"https://zipick.kr"`) {
		t.Fatalf("%+v", m)
	}
}

func TestBuild_광고는_제목에_광고를_붙이고_밤이면_아침으로_미룬다(t *testing.T) {
	s := newService(t)
	night := time.Date(2026, 10, 20, 22, 0, 0, 0, db.Seoul)
	m := s.Build(tpl(template.Marketing, "이번 주 새 공고", "본문", nil), ptr[int64](1), nil, nil, nil, nil, night, nil)
	if m.Title != "(광고) 이번 주 새 공고" {
		t.Fatalf("title %q", m.Title)
	}
	if want := time.Date(2026, 10, 21, 8, 0, 0, 0, db.Seoul); !m.ScheduledFor.Equal(want) {
		t.Fatalf("scheduled %v", m.ScheduledFor)
	}
	// 이미 (광고)로 시작하면 두 번 붙이지 않는다
	again := s.Build(tpl(template.Marketing, "(광고) 소식", "b", nil), nil, nil, nil, nil, nil, time.Date(2026, 10, 20, 12, 0, 0, 0, db.Seoul), nil)
	if again.Title != "(광고) 소식" {
		t.Fatalf("title %q", again.Title)
	}
}

func TestBuild_정보성은_밤에도_그대로_빈_링크는_nil(t *testing.T) {
	s := newService(t)
	night := time.Date(2026, 10, 20, 23, 0, 0, 0, db.Seoul)
	m := s.Build(tpl(template.Informational, "t", "b", ptr("{{detailUrl}}")), nil, ptr("a@b.c"), nil, nil, nil, night, nil)
	if !m.ScheduledFor.Equal(night) || m.URL != nil || *m.ToEmail != "a@b.c" {
		t.Fatalf("%+v", m)
	}
}

func TestBuild_예약_시각이_없으면_지금(t *testing.T) {
	m := newService(t).Build(tpl(template.Transactional, "t", "b", nil), nil, nil, nil, nil, nil, time.Time{}, nil)
	if time.Since(m.ScheduledFor) > time.Minute || m.ScheduledFor.Location() != db.Seoul {
		t.Fatalf("scheduled %v", m.ScheduledFor)
	}
}

func TestBuild_제목은_300자에서_자른다(t *testing.T) {
	long := strings.Repeat("가", 400)
	m := newService(t).Build(tpl(template.Transactional, long, "b", nil), nil, nil, nil, nil, nil, time.Time{}, nil)
	if n := len([]rune(m.Title)); n != 300 {
		t.Fatalf("len %d", n)
	}
}

func TestRequest_validate(t *testing.T) {
	ok := Request{TemplateCode: "DEADLINE", UserID: ptr[int64](1)}
	if ok.validate() != nil {
		t.Fatal("정상 요청을 거부")
	}
	cases := map[string]Request{
		"템플릿 코드 없음":   {UserID: ptr[int64](1)},
		"템플릿 코드 너무 김": {TemplateCode: strings.Repeat("A", 61), UserID: ptr[int64](1)},
		"받는 사람 없음":    {TemplateCode: "TEST"},
		"중복 키 너무 김":   {TemplateCode: "TEST", Email: ptr("a@b.c"), DedupeKey: ptr(strings.Repeat("k", 121))},
	}
	for name, r := range cases {
		if r.validate() == nil {
			t.Errorf("%s: 통과시킴", name)
		}
	}
}

func TestToResponse_시각을_Java_형식으로_중복_표시(t *testing.T) {
	sent := time.Date(2026, 10, 20, 10, 0, 0, 0, time.UTC) // DB 에서 온 값(UTC 라벨의 벽시계)
	r := ToResponse(Message{ID: 3, TemplateCode: "TEST", ScheduledFor: sent, SentAt: &sent, Status: Sent}, true)
	if !r.Duplicate || r.ID != 3 || r.ScheduledFor.Hour() != 10 || r.ScheduledFor.Location() != db.Seoul || r.SentAt == nil {
		t.Fatalf("%+v", r)
	}
}

func TestEmailAvailable과_anySupports(t *testing.T) {
	s := newService(t)
	if s.EmailAvailable() {
		t.Fatal("슬랙만 있는데 이메일 가능")
	}
	if s.anySupports(channel.Recipient{Email: ptr("a@b.c")}) || !s.anySupports(channel.Recipient{SlackWebhookURL: ptr("https://hooks.slack.com/x")}) {
		t.Fatal("anySupports")
	}
}
