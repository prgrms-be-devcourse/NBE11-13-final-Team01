package httpx

import (
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

// 응답 본문을 envelope 로 읽는 도우미
func body(t *testing.T, rec *httptest.ResponseRecorder) envelope {
	t.Helper()
	var e envelope
	if err := json.Unmarshal(rec.Body.Bytes(), &e); err != nil {
		t.Fatalf("응답이 JSON 이 아님: %s", rec.Body.String())
	}
	return e
}

func TestOK_메인서버와_같은_응답_형식(t *testing.T) {
	rec := httptest.NewRecorder()
	OK(rec, map[string]int{"id": 7})
	if rec.Code != 200 || !strings.Contains(rec.Header().Get("Content-Type"), "application/json") {
		t.Fatalf("status=%d type=%s", rec.Code, rec.Header().Get("Content-Type"))
	}
	if got := rec.Body.String(); !strings.Contains(got, `"success":true`) || !strings.Contains(got, `"error":null`) {
		t.Fatalf("got %s", got)
	}
}

func TestWriteError_코드마다_HTTP_상태가_정해져_있다(t *testing.T) {
	cases := map[Code]int{InvalidInput: 400, AuthRequired: 401, TemplateNotFound: 404, NoRecipient: 400}
	for code, want := range cases {
		rec := httptest.NewRecorder()
		WriteError(rec, Fail(code, "메시지"))
		if rec.Code != want {
			t.Errorf("%s: status %d, want %d", code, rec.Code, want)
		}
		if e := body(t, rec); e.Success || e.Error.Code != code || e.Error.Message != "메시지" {
			t.Errorf("%s: body %+v", code, e)
		}
	}
}

func TestWriteError_예상하지_못한_오류는_500_이고_내용을_숨긴다(t *testing.T) {
	rec := httptest.NewRecorder()
	WriteError(rec, errors.New("db password=secret"))
	e := body(t, rec)
	if rec.Code != 500 || e.Error.Code != InternalError || strings.Contains(rec.Body.String(), "secret") {
		t.Fatalf("status=%d body=%s", rec.Code, rec.Body.String())
	}
}

func TestDecode_잘못된_JSON은_INVALID_INPUT(t *testing.T) {
	req := httptest.NewRequest(http.MethodPost, "/", strings.NewReader("{not json"))
	var v map[string]any
	err := Decode(req, &v)
	var e *Error
	if !errors.As(err, &e) || e.Code != InvalidInput {
		t.Fatalf("got %v", err)
	}
	if Decode(httptest.NewRequest(http.MethodPost, "/", strings.NewReader(`{"a":1}`)), &v) != nil || v["a"] != 1.0 {
		t.Fatalf("정상 JSON 을 못 읽음: %v", v)
	}
}

func TestHandler_핸들러가_돌려준_오류를_응답으로_바꾼다(t *testing.T) {
	h := Handler(func(w http.ResponseWriter, r *http.Request) error { return Fail(TemplateNotFound, "없음") })
	rec := httptest.NewRecorder()
	h(rec, httptest.NewRequest(http.MethodGet, "/", nil))
	if rec.Code != 404 {
		t.Fatalf("status %d", rec.Code)
	}
	ok := Handler(func(w http.ResponseWriter, r *http.Request) error { OK(w, "x"); return nil })
	rec = httptest.NewRecorder()
	ok(rec, httptest.NewRequest(http.MethodGet, "/", nil))
	if rec.Code != 200 {
		t.Fatalf("status %d", rec.Code)
	}
}

func TestRequireInternalKey(t *testing.T) {
	next := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(204) })
	call := func(key, path, header string) int {
		req := httptest.NewRequest(http.MethodGet, path, nil)
		if header != "" {
			req.Header.Set(HeaderInternalKey, header)
		}
		rec := httptest.NewRecorder()
		RequireInternalKey(key, next).ServeHTTP(rec, req)
		return rec.Code
	}
	tests := []struct {
		name, key, path, header string
		want                    int
	}{
		{"키가 맞으면 통과", "k1", "/internal/templates", "k1", 204},
		{"키가 다르면 401", "k1", "/internal/templates", "k2", 401},
		{"키가 없으면 401", "k1", "/internal/templates", "", 401},
		{"서버에 키가 설정되지 않았으면 전부 거부", "", "/internal/templates", "", 401},
		{"/internal 밖(헬스체크)은 키 없이 통과", "k1", "/health", "", 204},
	}
	for _, tc := range tests {
		if got := call(tc.key, tc.path, tc.header); got != tc.want {
			t.Errorf("%s: got %d want %d", tc.name, got, tc.want)
		}
	}
}

func TestError_문자열(t *testing.T) {
	if Fail(NoChannel, "채널 없음").Error() != "NO_CHANNEL: 채널 없음" {
		t.Fatal(Fail(NoChannel, "채널 없음").Error())
	}
}
