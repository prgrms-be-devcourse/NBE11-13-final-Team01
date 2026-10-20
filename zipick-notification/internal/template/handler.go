package template

import (
	"net/http"
	"strings"

	"github.com/zipick/notification/internal/httpx"
)

// Handler 는 /internal/templates 의 HTTP 핸들러. Spring 의 @RestController.
type Handler struct{ repo *Repo }

func NewHandler(repo *Repo) *Handler { return &Handler{repo: repo} }

// Register 는 라우터에 경로를 등록한다. Go 1.22+ 는 "METHOD /path/{param}" 패턴을 지원한다.
func (h *Handler) Register(mux *http.ServeMux) {
	mux.HandleFunc("GET /internal/templates", httpx.Handler(h.list))
	mux.HandleFunc("GET /internal/templates/{code}", httpx.Handler(h.get))
	mux.HandleFunc("PUT /internal/templates/{code}", httpx.Handler(h.put))
	mux.HandleFunc("POST /internal/templates/{code}/preview", httpx.Handler(h.preview))
}

func (h *Handler) list(w http.ResponseWriter, r *http.Request) error {
	all, err := h.repo.FindAll(r.Context())
	if err != nil {
		return err
	}
	out := make([]Template, 0, len(all))
	for _, t := range all {
		out = append(out, t.WithVariables())
	}
	httpx.OK(w, out)
	return nil
}

func (h *Handler) get(w http.ResponseWriter, r *http.Request) error {
	t, err := h.repo.FindByCode(r.Context(), r.PathValue("code"))
	if err != nil {
		return err
	}
	httpx.OK(w, t.WithVariables())
	return nil
}

// put: 템플릿 만들기/고치기. 코드는 대문자로 통일한다.
func (h *Handler) put(w http.ResponseWriter, r *http.Request) error {
	var req Request
	if err := httpx.Decode(r, &req); err != nil {
		return err
	}
	if err := req.Validate(); err != nil {
		return err
	}
	code := strings.ToUpper(r.PathValue("code"))
	url := req.URLTemplate
	if url != nil && strings.TrimSpace(*url) == "" {
		url = nil
	}
	t := Template{Code: code, Name: req.Name, Category: req.Category, TitleTemplate: req.TitleTemplate,
		BodyTemplate: req.BodyTemplate, URLTemplate: url, Description: req.Description, Enabled: req.Enabled}
	if err := h.repo.Upsert(r.Context(), t); err != nil {
		return err
	}
	httpx.OK(w, t.WithVariables())
	return nil
}

// preview: 운영자가 변수를 넣어 보는 미리보기.
func (h *Handler) preview(w http.ResponseWriter, r *http.Request) error {
	vars := map[string]any{}
	if r.ContentLength != 0 {
		if err := httpx.Decode(r, &vars); err != nil {
			return err
		}
	}
	t, err := h.repo.FindByCode(r.Context(), r.PathValue("code"))
	if err != nil {
		return err
	}
	title, body := Render(t.TitleTemplate, vars), Render(t.BodyTemplate, vars)
	var urlText string
	var urlMissing []string
	if t.URLTemplate != nil {
		u := Render(*t.URLTemplate, vars)
		urlText, urlMissing = u.Text, u.Missing
	}
	missing := map[string]bool{}
	for _, m := range [][]string{title.Missing, body.Missing, urlMissing} {
		for _, k := range m {
			missing[k] = true
		}
	}
	httpx.OK(w, map[string]any{
		"title": title.Text, "body": body.Text, "url": urlText, "missingVariables": sortedKeys(missing),
	})
	return nil
}
