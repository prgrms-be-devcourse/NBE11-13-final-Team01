// Package template 은 메시지 템플릿이다. 템플릿은 저장소의 templates/*.yaml 파일이고, 서버가 뜰 때 한 번 읽는다.
//
// 알림센터는 문구를 "가지고" 있을 뿐 판단하지 않는다. 요청이 템플릿 코드와 변수를 주면 채널별 문구에 끼워 넣는다.
// 새 용도가 생기면 yaml 파일 하나를 PR 로 추가하면 된다 — 알림센터 코드는 바뀌지 않는다.
//
//	code: COLLECTOR_DAILY_REPORT         # {영역}_{용도}. 요청의 templateCode
//	owner: 김주형 (공고 수집)             # 문구 담당자
//	variables: [date, newNotices]        # 쓰는 변수. 빠지면 빈칸으로 나가고 응답의 missingVariables 로 알려 준다
//	email:                               # 채널별 문구. 요청한 채널의 문구가 없으면 요청이 거부된다
//	  subject: "[ZIPICK 운영] {{date}} 수집 결과"
//	  body: "신규 {{newNotices}}건"
//	slack:
//	  text: "*{{date}} 수집* 신규 {{newNotices}}"
package template

import (
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strings"

	"gopkg.in/yaml.v3"
)

// Template 은 yaml 파일 하나.
type Template struct {
	Code        string   `yaml:"code" json:"code"`
	Owner       string   `yaml:"owner" json:"owner"`
	Description string   `yaml:"description" json:"description"`
	Variables   []string `yaml:"variables" json:"variables"`
	Email       *Email   `yaml:"email" json:"email"` // nil 이면 이 템플릿은 이메일로 보낼 수 없다
	Slack       *Slack   `yaml:"slack" json:"slack"`
}

type Email struct {
	Subject string `yaml:"subject" json:"subject"`
	Body    string `yaml:"body" json:"body"`
}

type Slack struct {
	Text string `yaml:"text" json:"text"`
}

// Channels 는 이 템플릿이 문구를 가진 채널 이름.
func (t Template) Channels() []string {
	var out []string
	if t.Email != nil {
		out = append(out, "email")
	}
	if t.Slack != nil {
		out = append(out, "slack")
	}
	return out
}

// HasChannel 은 요청한 채널의 문구가 있는지.
func (t Template) HasChannel(name string) bool {
	switch name {
	case "email":
		return t.Email != nil
	case "slack":
		return t.Slack != nil
	}
	return false
}

var codePattern = regexp.MustCompile(`^[A-Z][A-Z0-9]*(_[A-Z0-9]+)+$`)

func (t Template) validate(file string) error {
	switch {
	case !codePattern.MatchString(t.Code):
		return fmt.Errorf("%s: code 는 대문자와 _ 로 '{영역}_{용도}' 꼴이어야 합니다 (예 AUTH_EMAIL_VERIFY): %q", file, t.Code)
	case t.Email == nil && t.Slack == nil:
		return fmt.Errorf("%s: email 또는 slack 문구가 하나는 있어야 합니다", file)
	case t.Email != nil && (strings.TrimSpace(t.Email.Subject) == "" || strings.TrimSpace(t.Email.Body) == ""):
		return fmt.Errorf("%s: email 은 subject 와 body 가 모두 필요합니다", file)
	case t.Slack != nil && strings.TrimSpace(t.Slack.Text) == "":
		return fmt.Errorf("%s: slack 은 text 가 필요합니다", file)
	}
	return nil
}

// Store 는 읽어 둔 템플릿 모음. 읽기 전용이라 잠금이 필요 없다.
type Store struct {
	byCode map[string]Template
	order  []string
}

// Load 는 디렉터리의 *.yaml 을 모두 읽는다. 코드가 겹치면 실패한다 (기동 시점에 바로 알게).
func Load(dir string) (*Store, error) {
	files, err := filepath.Glob(filepath.Join(dir, "*.yaml"))
	if err != nil {
		return nil, err
	}
	if len(files) == 0 {
		return nil, fmt.Errorf("템플릿 파일이 없습니다: %s/*.yaml", dir)
	}
	sort.Strings(files)
	s := &Store{byCode: map[string]Template{}}
	for _, f := range files {
		raw, err := os.ReadFile(f)
		if err != nil {
			return nil, err
		}
		var t Template
		if err := yaml.Unmarshal(raw, &t); err != nil {
			return nil, fmt.Errorf("%s: yaml 오류: %w", f, err)
		}
		if err := t.validate(filepath.Base(f)); err != nil {
			return nil, err
		}
		if _, dup := s.byCode[t.Code]; dup {
			return nil, fmt.Errorf("%s: 템플릿 코드가 겹칩니다: %s", f, t.Code)
		}
		if t.Variables == nil {
			t.Variables = VariablesOf(t)
		}
		s.byCode[t.Code] = t
		s.order = append(s.order, t.Code)
	}
	return s, nil
}

// FromList 는 테스트용: 파일 없이 템플릿을 넣어 만든다.
func FromList(ts ...Template) *Store {
	s := &Store{byCode: map[string]Template{}}
	for _, t := range ts {
		if t.Variables == nil {
			t.Variables = VariablesOf(t)
		}
		s.byCode[t.Code] = t
		s.order = append(s.order, t.Code)
	}
	return s
}

// Get 은 없으면 (Template{}, false).
func (s *Store) Get(code string) (Template, bool) {
	t, ok := s.byCode[strings.ToUpper(strings.TrimSpace(code))]
	return t, ok
}

// All 은 파일 이름 순.
func (s *Store) All() []Template {
	out := make([]Template, 0, len(s.order))
	for _, c := range s.order {
		out = append(out, s.byCode[c])
	}
	return out
}

// VariablesOf 는 모든 채널 문구에서 {{변수}} 이름을 모은다.
func VariablesOf(t Template) []string {
	var parts []string
	if t.Email != nil {
		parts = append(parts, t.Email.Subject, t.Email.Body)
	}
	if t.Slack != nil {
		parts = append(parts, t.Slack.Text)
	}
	return variableNames(parts...)
}
