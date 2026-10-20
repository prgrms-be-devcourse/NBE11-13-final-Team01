package template

import (
	"reflect"
	"testing"
)

// Go 테스트는 파일 이름이 _test.go 로 끝나고 함수가 Test 로 시작하면 된다. 실행: go test ./...
func TestRender(t *testing.T) {
	r := Render("접수 마감 {{dDayLabel}} · {{ noticeTitle }}", map[string]any{"dDayLabel": "D-3", "noticeTitle": "행복주택"})
	if r.Text != "접수 마감 D-3 · 행복주택" || len(r.Missing) != 0 {
		t.Fatalf("got %+v", r)
	}
}

func TestRenderMissing(t *testing.T) {
	r := Render("{{a}}-{{b}}", map[string]any{"a": 1})
	if r.Text != "1-" || !reflect.DeepEqual(r.Missing, []string{"b"}) {
		t.Fatalf("got %+v", r)
	}
}

func TestRenderSpecialChars(t *testing.T) {
	if got := Render("{{v}}", map[string]any{"v": "$1 \\ok"}).Text; got != "$1 \\ok" {
		t.Fatalf("got %q", got)
	}
}

func TestVariablesOf(t *testing.T) {
	a, b := "{{x}} {{y}}", "{{x}}/{{z}}"
	if got := VariablesOf(&a, nil, &b); !reflect.DeepEqual(got, []string{"x", "y", "z"}) {
		t.Fatalf("got %v", got)
	}
}
