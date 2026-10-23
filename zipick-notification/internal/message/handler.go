package message

import (
	"net/http"
	"strconv"
	"strings"

	"github.com/zipick/notification/internal/httpx"
)

// Handler 는 /internal/v1 의 알림 요청·조회·묶음·채널 상태.
type Handler struct{ svc *Service }

func NewHandler(svc *Service) *Handler { return &Handler{svc: svc} }

func (h *Handler) Register(mux *http.ServeMux) {
	mux.HandleFunc("POST /internal/v1/notifications", httpx.Handler(h.enqueue))
	mux.HandleFunc("GET /internal/v1/notifications", httpx.Handler(h.search))
	mux.HandleFunc("GET /internal/v1/notifications/{id}", httpx.Handler(h.get))
	mux.HandleFunc("POST /internal/v1/batches", httpx.Handler(h.createBatch))
	mux.HandleFunc("GET /internal/v1/batches/{id}", httpx.Handler(h.getBatch))
	mux.HandleFunc("DELETE /internal/v1/batches/{id}", httpx.Handler(h.cancelBatch))
	mux.HandleFunc("GET /internal/v1/channels", httpx.Handler(h.channels))
}

// enqueue: 요청 하나를 적는다. dedupeKey 가 같으면 duplicate=true 로 기존 건. delivery=now 면 바로 보내고 결과까지.
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

// search: ?tag=user:2&tag=kind:x&source=main → 최근 50건. tag 는 "키:값".
func (h *Handler) search(w http.ResponseWriter, r *http.Request) error {
	tags := map[string]string{}
	for _, t := range r.URL.Query()["tag"] {
		k, v, ok := strings.Cut(t, ":")
		if !ok || k == "" {
			return httpx.Fail(httpx.InvalidInput, "tag 는 키:값 꼴이어야 합니다 (예 user:2)")
		}
		tags[k] = v
	}
	source := r.URL.Query().Get("source")
	if len(tags) == 0 && source == "" {
		return httpx.Fail(httpx.InvalidInput, "tag 또는 source 중 하나는 있어야 합니다")
	}
	res, err := h.svc.Search(r.Context(), tags, source)
	if err != nil {
		return err
	}
	httpx.OK(w, res)
	return nil
}

func (h *Handler) get(w http.ResponseWriter, r *http.Request) error {
	id, err := pathID(r)
	if err != nil {
		return err
	}
	res, err := h.svc.Get(r.Context(), id)
	if err != nil {
		return err
	}
	httpx.OK(w, res)
	return nil
}

func (h *Handler) createBatch(w http.ResponseWriter, r *http.Request) error {
	var req BatchRequest
	if err := httpx.Decode(r, &req); err != nil {
		return err
	}
	res, err := h.svc.CreateBatch(r.Context(), req)
	if err != nil {
		return err
	}
	httpx.OK(w, res)
	return nil
}

func (h *Handler) getBatch(w http.ResponseWriter, r *http.Request) error {
	id, err := pathID(r)
	if err != nil {
		return err
	}
	res, err := h.svc.GetBatch(r.Context(), id)
	if err != nil {
		return err
	}
	httpx.OK(w, res)
	return nil
}

func (h *Handler) cancelBatch(w http.ResponseWriter, r *http.Request) error {
	id, err := pathID(r)
	if err != nil {
		return err
	}
	res, err := h.svc.CancelBatch(r.Context(), id)
	if err != nil {
		return err
	}
	httpx.OK(w, res)
	return nil
}

// channels: 이 서버에 켜진 채널. 설정 화면의 "이메일을 쓸 수 있는가" 표시용.
func (h *Handler) channels(w http.ResponseWriter, r *http.Request) error {
	httpx.OK(w, h.svc.Available())
	return nil
}

func pathID(r *http.Request) (int64, error) {
	id, err := strconv.ParseInt(r.PathValue("id"), 10, 64)
	if err != nil {
		return 0, httpx.Fail(httpx.InvalidInput, "id 는 숫자여야 합니다")
	}
	return id, nil
}
