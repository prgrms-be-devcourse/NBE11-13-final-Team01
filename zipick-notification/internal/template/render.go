package template

import (
	"fmt"
	"regexp"
	"sort"
)

// {{ 변수 }} 자리. 공백 허용, 이름은 영문·숫자·_·.
var varPattern = regexp.MustCompile(`\{\{\s*([A-Za-z0-9_.]+)\s*}}`)

// Rendered 는 치환 결과. Missing 은 값이 없던 변수 이름 — 호출한 쪽이 로그로 남겨 템플릿 실수를 찾게 한다.
type Rendered struct {
	Text    string
	Missing []string
}

// Render 는 {{변수}} 를 values 로 채운다. 값이 없는 변수는 빈 문자열로 둔다.
// 의도적으로 단순하게 둔다 (조건·반복 없음). 문구 분기가 필요하면 템플릿을 하나 더 만든다.
func Render(tpl string, values map[string]any) Rendered {
	missing := map[string]bool{}
	text := varPattern.ReplaceAllStringFunc(tpl, func(m string) string {
		key := varPattern.FindStringSubmatch(m)[1]
		v, ok := values[key]
		if !ok || v == nil {
			missing[key] = true
			return ""
		}
		return fmt.Sprint(v) // 숫자·불리언도 문자열로
	})
	return Rendered{Text: text, Missing: sortedKeys(missing)}
}

// VariablesOf 는 템플릿들이 쓰는 변수 이름 목록 (운영자 화면·검증용).
func VariablesOf(tpls ...*string) []string {
	names := map[string]bool{}
	for _, t := range tpls {
		if t == nil {
			continue
		}
		for _, m := range varPattern.FindAllStringSubmatch(*t, -1) {
			names[m[1]] = true
		}
	}
	return sortedKeys(names)
}

func sortedKeys(m map[string]bool) []string {
	out := make([]string, 0, len(m))
	for k := range m {
		out = append(out, k)
	}
	sort.Strings(out)
	return out
}

// WithVariables 는 응답용으로 Variables 를 채운 복사본을 돌려준다.
func (t Template) WithVariables() Template {
	t.Variables = VariablesOf(&t.TitleTemplate, &t.BodyTemplate, t.URLTemplate)
	if t.Variables == nil {
		t.Variables = []string{} // JSON 에 null 대신 [] 로
	}
	return t
}
