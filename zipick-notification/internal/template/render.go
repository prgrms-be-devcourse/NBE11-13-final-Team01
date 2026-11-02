package template

import (
	"fmt"
	"regexp"
	"sort"
)

// {{ 변수 }} 자리. 공백 허용, 이름은 영문·숫자·_·.
var varPattern = regexp.MustCompile(`\{\{\s*([A-Za-z0-9_.]+)\s*}}`)

// Rendered 는 한 채널의 완성된 문구. Subject 는 이메일에만 있다.
type Rendered struct {
	Subject string
	Body    string
}

// RenderAll 은 요청한 채널들의 문구를 채운다. 반환: 채널 → 문구, 빈 변수 이름(정렬).
// 값이 없는 변수는 빈칸으로 둔다 (팀 결정: 발송은 막지 않고 응답으로 알려 준다).
func (t Template) RenderAll(channels []string, values map[string]any) (map[string]Rendered, []string) {
	out := map[string]Rendered{}
	missing := map[string]bool{}
	for _, ch := range channels {
		switch ch {
		case "email":
			if t.Email == nil {
				continue
			}
			out[ch] = Rendered{Subject: fill(t.Email.Subject, values, missing), Body: fill(t.Email.Body, values, missing)}
		case "slack":
			if t.Slack == nil {
				continue
			}
			out[ch] = Rendered{Body: fill(t.Slack.Text, values, missing)}
		}
	}
	return out, sortedKeys(missing)
}

// Fill 은 문자열 하나를 채운다 (미리보기·테스트용).
func Fill(tpl string, values map[string]any) (string, []string) {
	missing := map[string]bool{}
	return fill(tpl, values, missing), sortedKeys(missing)
}

func fill(tpl string, values map[string]any, missing map[string]bool) string {
	return varPattern.ReplaceAllStringFunc(tpl, func(m string) string {
		key := varPattern.FindStringSubmatch(m)[1]
		v, ok := values[key]
		if !ok || v == nil {
			missing[key] = true
			return ""
		}
		return fmt.Sprint(v)
	})
}

func variableNames(tpls ...string) []string {
	names := map[string]bool{}
	for _, t := range tpls {
		for _, m := range varPattern.FindAllStringSubmatch(t, -1) {
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
