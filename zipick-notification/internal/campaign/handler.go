package campaign

import (
	"net/http"
	"strconv"

	"github.com/zipick/notification/internal/httpx"
)

// Handler 는 /internal/campaigns: 운영자가 템플릿·대상·시각을 정해 두면 그 시각에 일괄 발송.
type Handler struct{ svc *Service }

func NewHandler(svc *Service) *Handler { return &Handler{svc: svc} }

func (h *Handler) Register(mux *http.ServeMux) {
	mux.HandleFunc("GET /internal/campaigns", httpx.Handler(h.list))
	mux.HandleFunc("POST /internal/campaigns", httpx.Handler(h.create))
	mux.HandleFunc("GET /internal/campaigns/{id}", httpx.Handler(h.get))
	mux.HandleFunc("DELETE /internal/campaigns/{id}", httpx.Handler(h.cancel))
}

func pathID(r *http.Request) (int64, error) {
	id, err := strconv.ParseInt(r.PathValue("id"), 10, 64)
	if err != nil {
		return 0, httpx.Fail(httpx.InvalidInput, "id 는 숫자여야 합니다.")
	}
	return id, nil
}

func (h *Handler) list(w http.ResponseWriter, r *http.Request) error {
	out, err := h.svc.List(r.Context())
	if err != nil {
		return err
	}
	httpx.OK(w, out)
	return nil
}

func (h *Handler) create(w http.ResponseWriter, r *http.Request) error {
	var req Request
	if err := httpx.Decode(r, &req); err != nil {
		return err
	}
	out, err := h.svc.Create(r.Context(), req)
	if err != nil {
		return err
	}
	httpx.OK(w, out)
	return nil
}

func (h *Handler) get(w http.ResponseWriter, r *http.Request) error {
	id, err := pathID(r)
	if err != nil {
		return err
	}
	out, err := h.svc.Get(r.Context(), id)
	if err != nil {
		return err
	}
	httpx.OK(w, out)
	return nil
}

func (h *Handler) cancel(w http.ResponseWriter, r *http.Request) error {
	id, err := pathID(r)
	if err != nil {
		return err
	}
	out, err := h.svc.Cancel(r.Context(), id)
	if err != nil {
		return err
	}
	httpx.OK(w, out)
	return nil
}
