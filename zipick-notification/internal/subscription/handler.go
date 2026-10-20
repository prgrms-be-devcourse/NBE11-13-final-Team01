package subscription

import (
	"context"
	"net/http"
	"strconv"

	"github.com/zipick/notification/internal/httpx"
)

// TestSender 는 테스트 발송을 맡길 쪽 (message.Service). 인터페이스로 받아 패키지가 서로 import 하지 않게 한다.
type TestSender interface {
	SendTest(ctx context.Context, userID int64) (any, error)
	EmailAvailable() bool
}

// Handler 는 /internal/subscriptions: 사용자별 수신 주소·동의 (메인 서버의 알림 설정 화면이 그대로 넘겨 준다).
type Handler struct {
	repo *Repo
	test TestSender
}

func NewHandler(repo *Repo, test TestSender) *Handler { return &Handler{repo: repo, test: test} }

func (h *Handler) Register(mux *http.ServeMux) {
	mux.HandleFunc("GET /internal/subscriptions", httpx.Handler(h.listEnabled))
	mux.HandleFunc("GET /internal/subscriptions/{userId}", httpx.Handler(h.get))
	mux.HandleFunc("PUT /internal/subscriptions/{userId}", httpx.Handler(h.put))
	mux.HandleFunc("POST /internal/subscriptions/{userId}/test", httpx.Handler(h.sendTest))
}

func userID(r *http.Request) (int64, error) {
	id, err := strconv.ParseInt(r.PathValue("userId"), 10, 64)
	if err != nil {
		return 0, httpx.Fail(httpx.InvalidInput, "userId 는 숫자여야 합니다.")
	}
	return id, nil
}

// listEnabled: 정보성 알림이 켜진 사용자 전체 (메인 스케줄러용).
func (h *Handler) listEnabled(w http.ResponseWriter, r *http.Request) error {
	list, err := h.repo.FindEnabled(r.Context())
	if err != nil {
		return err
	}
	out := make([]Response, 0, len(list))
	for _, s := range list {
		out = append(out, ToResponse(s, h.test.EmailAvailable()))
	}
	httpx.OK(w, out)
	return nil
}

func (h *Handler) get(w http.ResponseWriter, r *http.Request) error {
	id, err := userID(r)
	if err != nil {
		return err
	}
	s, err := h.repo.FindByUser(r.Context(), id)
	if err != nil {
		return err
	}
	if s == nil {
		httpx.OK(w, EmptyResponse(id, h.test.EmailAvailable()))
		return nil
	}
	httpx.OK(w, ToResponse(*s, h.test.EmailAvailable()))
	return nil
}

func (h *Handler) put(w http.ResponseWriter, r *http.Request) error {
	id, err := userID(r)
	if err != nil {
		return err
	}
	var req Request
	if err := httpx.Decode(r, &req); err != nil {
		return err
	}
	if err := req.Validate(); err != nil {
		return err
	}
	s, err := h.repo.Save(r.Context(), id, req)
	if err != nil {
		return err
	}
	httpx.OK(w, ToResponse(s, h.test.EmailAvailable()))
	return nil
}

func (h *Handler) sendTest(w http.ResponseWriter, r *http.Request) error {
	id, err := userID(r)
	if err != nil {
		return err
	}
	res, err := h.test.SendTest(r.Context(), id)
	if err != nil {
		return err
	}
	httpx.OK(w, res)
	return nil
}
