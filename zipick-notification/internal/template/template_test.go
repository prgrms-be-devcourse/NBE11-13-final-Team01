package template

import (
	"os"
	"path/filepath"
	"reflect"
	"testing"
)

func write(t *testing.T, dir, name, body string) {
	t.Helper()
	if err := os.WriteFile(filepath.Join(dir, name), []byte(body), 0o600); err != nil {
		t.Fatal(err)
	}
}

func TestLoad_파일을_읽고_변수를_모은다(t *testing.T) {
	dir := t.TempDir()
	write(t, dir, "a.yaml", "code: AUTH_EMAIL_VERIFY\nowner: 한채희\nemail:\n  subject: \"코드 {{code}}\"\n  body: \"{{name}}님, {{code}}\"\n")
	write(t, dir, "b.yaml", "code: OPS_DAILY_REPORT\nslack:\n  text: \"{{date}} 결과\"\n")
	s, err := Load(dir)
	if err != nil {
		t.Fatal(err)
	}
	a, ok := s.Get("auth_email_verify") // 대소문자 무관
	if !ok || !reflect.DeepEqual(a.Variables, []string{"code", "name"}) || !reflect.DeepEqual(a.Channels(), []string{"email"}) {
		t.Fatalf("got %+v", a)
	}
	if len(s.All()) != 2 || s.All()[1].Code != "OPS_DAILY_REPORT" {
		t.Fatalf("got %+v", s.All())
	}
}

func TestLoad_잘못된_파일은_기동_실패(t *testing.T) {
	cases := map[string]string{
		"소문자 코드":   "code: auth_verify\nslack:\n  text: x\n",
		"문구 없음":    "code: AUTH_VERIFY\n",
		"제목 없는 메일": "code: AUTH_VERIFY\nemail:\n  body: x\n",
	}
	for name, body := range cases {
		dir := t.TempDir()
		write(t, dir, "x.yaml", body)
		if _, err := Load(dir); err == nil {
			t.Errorf("%s: 오류가 나야 함", name)
		}
	}
	dir := t.TempDir()
	write(t, dir, "a.yaml", "code: AUTH_VERIFY\nslack:\n  text: a\n")
	write(t, dir, "b.yaml", "code: AUTH_VERIFY\nslack:\n  text: b\n")
	if _, err := Load(dir); err == nil {
		t.Error("코드가 겹치면 오류가 나야 함")
	}
}

func TestRenderAll_채널별로_채우고_빈_변수를_알려준다(t *testing.T) {
	tpl := Template{Code: "A_B", Email: &Email{Subject: "제목 {{a}}", Body: "본문 {{b}}"}, Slack: &Slack{Text: "슬랙 {{a}} {{c}}"}}
	out, missing := tpl.RenderAll([]string{"email", "slack"}, map[string]any{"a": 1})
	if out["email"].Subject != "제목 1" || out["email"].Body != "본문 " || out["slack"].Body != "슬랙 1 " {
		t.Fatalf("got %+v", out)
	}
	if !reflect.DeepEqual(missing, []string{"b", "c"}) {
		t.Fatalf("missing %v", missing)
	}
	// 문구가 없는 채널은 결과에 없다
	out, _ = Template{Code: "A_B", Slack: &Slack{Text: "x"}}.RenderAll([]string{"email", "slack"}, nil)
	if _, has := out["email"]; has || out["slack"].Body != "x" {
		t.Fatalf("got %+v", out)
	}
}

func TestFill_특수문자와_nil맵(t *testing.T) {
	if s, _ := Fill("{{v}}", map[string]any{"v": "$1 \\ok"}); s != "$1 \\ok" {
		t.Fatalf("got %q", s)
	}
	if s, m := Fill("{{v}}", nil); s != "" || !reflect.DeepEqual(m, []string{"v"}) {
		t.Fatalf("got %q %v", s, m)
	}
}
