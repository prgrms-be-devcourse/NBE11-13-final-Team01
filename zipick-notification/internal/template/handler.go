package template

import (
	"net/http"
	"strings"

	"github.com/zipick/notification/internal/httpx"
)

// Handler 는 /internal/v1/templates — 읽기 전용. 문구를 바꾸려면 yaml 파일을 고쳐 PR 을 올린다.
type Handler struct{ store *Store }

func NewHandler(store *Store) *Handler { return &Handler{store: store} }

func (h *Handler) Register(mux *http.ServeMux) {
	mux.HandleFunc("GET /internal/v1/templates", httpx.Handler(h.list))
	mux.HandleFunc("GET /internal/v1/templates/{code}", httpx.Handler(h.get))
	mux.HandleFunc("POST /internal/v1/templates/{code}/preview", httpx.Handler(h.preview))
}

func (h *Handler) list(w http.ResponseWriter, r *http.Request) error {
	httpx.OK(w, h.store.All())
	return nil
}

func (h *Handler) get(w http.ResponseWriter, r *http.Request) error {
	t, ok := h.store.Get(r.PathValue("code"))
	if !ok {
		return httpx.Fail(httpx.TemplateNotFound, "템플릿이 없습니다: "+strings.ToUpper(r.PathValue("code")))
	}
	httpx.OK(w, t)
	return nil
}

// preview: 변수를 넣어 채널별 문구를 미리 본다. 본문 = variables 맵.
func (h *Handler) preview(w http.ResponseWriter, r *http.Request) error {
	t, ok := h.store.Get(r.PathValue("code"))
	if !ok {
		return httpx.Fail(httpx.TemplateNotFound, "템플릿이 없습니다: "+strings.ToUpper(r.PathValue("code")))
	}
	vars := map[string]any{}
	if r.ContentLength != 0 {
		if err := httpx.Decode(r, &vars); err != nil {
			return err
		}
	}
	rendered, missing := t.RenderAll(t.Channels(), vars)
	httpx.OK(w, map[string]any{"rendered": rendered, "missingVariables": missing})
	return nil
}
