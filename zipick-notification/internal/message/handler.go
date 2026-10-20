package message

import (
	"net/http"
	"strconv"

	"github.com/zipick/notification/internal/httpx"
)

// Handler 는 /internal/notifications: 다른 서버가 '템플릿 코드 + 변수'로 알림을 요청한다.
type Handler struct{ svc *Service }

func NewHandler(svc *Service) *Handler { return &Handler{svc: svc} }

func (h *Handler) Register(mux *http.ServeMux) {
	mux.HandleFunc("POST /internal/notifications", httpx.Handler(h.enqueue))
	mux.HandleFunc("GET /internal/notifications", httpx.Handler(h.history))
}

// enqueue: 아웃박스에 적는다. dedupeKey 가 같으면 duplicate=true 로 기존 건을 돌려준다.
func (h *Handler) enqueue(w http.ResponseWriter, r *http.Request) error {
	var req Request
	if err := httpx.Decode(r, &req); err != nil {
		return err
	}
	res, err := h.svc.Enqueue(r.Context(), req)
	if err != nil {
		return err
	}
	httpx.OK(w, res)
	return nil
}

// history: 사용자의 알림 내역 (최근 50건). ?userId=
func (h *Handler) history(w http.ResponseWriter, r *http.Request) error {
	userID, err := strconv.ParseInt(r.URL.Query().Get("userId"), 10, 64)
	if err != nil {
		return httpx.Fail(httpx.InvalidInput, "userId 가 필요합니다.")
	}
	res, err := h.svc.History(r.Context(), userID)
	if err != nil {
		return err
	}
	httpx.OK(w, res)
	return nil
}
