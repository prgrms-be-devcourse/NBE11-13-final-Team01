package httpx

import (
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestHandler_ErrorMapsToStatusAndCode(t *testing.T) {
	h := Handler(func(w http.ResponseWriter, r *http.Request) error { return Fail(ChannelUnavailable, "꺼짐") })
	rec := httptest.NewRecorder()
	h(rec, httptest.NewRequest("GET", "/", nil))
	if rec.Code != 400 || !strings.Contains(rec.Body.String(), `"CHANNEL_UNAVAILABLE"`) || !strings.Contains(rec.Body.String(), `"success":false`) {
		t.Fatalf("%d %s", rec.Code, rec.Body.String())
	}
	for code, want := range map[Code]int{MessageNotFound: 404, BatchNotFound: 404, NoRecipient: 400, TemplateNotFound: 404, InternalError: 500} {
		if statusOf[code] != want {
			t.Errorf("%s → %d, want %d", code, statusOf[code], want)
		}
	}
	rec = httptest.NewRecorder()
	Handler(func(http.ResponseWriter, *http.Request) error { return errors.New("db") })(rec, httptest.NewRequest("GET", "/", nil))
	if rec.Code != 500 || strings.Contains(rec.Body.String(), "db") {
		t.Fatalf("내부 오류 내용은 감춰야 함: %d %s", rec.Code, rec.Body.String())
	}
}

func TestRequireInternalKey(t *testing.T) {
	next := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(204) })
	h := RequireInternalKey("secret", next)
	for _, c := range []struct {
		path, key string
		want      int
	}{{"/health", "", 204}, {"/internal/v1/channels", "", 401}, {"/internal/v1/channels", "wrong", 401}, {"/internal/v1/channels", "secret", 204}} {
		req := httptest.NewRequest("GET", c.path, nil)
		if c.key != "" {
			req.Header.Set("X-Internal-Key", c.key)
		}
		rec := httptest.NewRecorder()
		h.ServeHTTP(rec, req)
		if rec.Code != c.want {
			t.Errorf("%s key=%q → %d, want %d", c.path, c.key, rec.Code, c.want)
		}
	}
}

func TestDecode_IgnoresUnknownRejectsEmpty(t *testing.T) {
	var v struct {
		A string `json:"a"`
	}
	if err := Decode(httptest.NewRequest("POST", "/", strings.NewReader(`{"a":"x","zzz":1}`)), &v); err != nil {
		t.Fatal("모르는 필드는 무시 (Spring 기본과 같음)")
	}
	if err := Decode(httptest.NewRequest("POST", "/", strings.NewReader(``)), &v); err == nil {
		t.Fatal("빈 본문은 거부")
	}
	if err := Decode(httptest.NewRequest("POST", "/", strings.NewReader(`{"a":"x"}`)), &v); err != nil || v.A != "x" {
		t.Fatal(err)
	}
	var env map[string]any
	rec := httptest.NewRecorder()
	OK(rec, 1)
	_ = json.Unmarshal(rec.Body.Bytes(), &env)
	if env["success"] != true || env["data"] != float64(1) {
		t.Fatalf("%v", env)
	}
}
