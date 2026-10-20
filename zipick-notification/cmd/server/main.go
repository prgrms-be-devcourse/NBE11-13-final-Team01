// 알림센터 진입점. 설정 → DB → 채널 → 서비스 → 라우터 → 스케줄러 순으로 조립한다.
// Spring 이 어노테이션으로 자동 연결하는 것을 Go 는 여기서 손으로 한다. 의존 관계가 한눈에 보이는 것이 장점이다.
package main

import (
	"context"
	"errors"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/zipick/notification/internal/campaign"
	"github.com/zipick/notification/internal/channel"
	"github.com/zipick/notification/internal/config"
	"github.com/zipick/notification/internal/db"
	"github.com/zipick/notification/internal/httpx"
	"github.com/zipick/notification/internal/message"
	"github.com/zipick/notification/internal/queue"
	"github.com/zipick/notification/internal/scheduler"
	"github.com/zipick/notification/internal/subscription"
	"github.com/zipick/notification/internal/template"
)

func main() {
	if err := run(); err != nil {
		slog.Error("서버 시작 실패", "err", err)
		os.Exit(1)
	}
}

func run() error {
	cfg, err := config.Load()
	if err != nil {
		return err
	}
	// ctx 는 Ctrl+C / docker stop(SIGTERM) 때 취소된다 → 스케줄러와 서버가 정리하고 끝난다
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	pool, err := db.Connect(ctx, cfg.DBURL)
	if err != nil {
		return err
	}
	defer pool.Close()

	channels, err := channel.Build(ctx, cfg.Email)
	if err != nil {
		return err
	}

	// 저장소 → 서비스 → 핸들러
	templates := template.NewRepo(pool)
	subs := subscription.NewRepo(pool)
	messages := message.NewRepo(pool)
	msgSvc, err := message.NewService(messages, subs, templates, channels, cfg)
	if err != nil {
		return err
	}
	campaigns := campaign.NewService(pool, messages, msgSvc, subs, templates)

	if err := template.Seed(ctx, templates); err != nil {
		return err
	}

	mux := http.NewServeMux()
	mux.HandleFunc("GET /health", func(w http.ResponseWriter, _ *http.Request) { httpx.OK(w, map[string]string{"status": "UP"}) })
	template.NewHandler(templates).Register(mux)
	subscription.NewHandler(subs, testAdapter{msgSvc}).Register(mux)
	message.NewHandler(msgSvc).Register(mux)
	campaign.NewHandler(campaigns).Register(mux)

	if cfg.Scheduler {
		go scheduler.Run(ctx, cfg.PollEvery, 15*time.Second, campaigns, msgSvc)
	}
	// 큐 리스너: 메인 서버가 던진 알림 요청을 받는다. RABBITMQ_URL 이 없으면 HTTP 로만 받는다
	if cfg.RabbitURL != "" {
		go queue.Run(ctx, cfg.RabbitURL, msgSvc)
	}

	server := &http.Server{
		Addr:              ":" + cfg.Port,
		Handler:           httpx.RequireInternalKey(cfg.InternalKey, mux),
		ReadHeaderTimeout: 5 * time.Second,
	}
	go func() {
		<-ctx.Done()
		shutdownCtx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		_ = server.Shutdown(shutdownCtx)
	}()
	slog.Info("알림센터 시작", "port", cfg.Port, "email", cfg.Email.Provider, "scheduler", cfg.Scheduler, "keySet", cfg.InternalKey != "", "queue", cfg.RabbitURL != "")
	if err := server.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
		return err
	}
	return nil
}

// testAdapter 는 message.Service 를 subscription.TestSender 인터페이스에 맞춘다 (반환 타입만 any 로).
type testAdapter struct{ svc *message.Service }

func (a testAdapter) SendTest(ctx context.Context, userID int64) (any, error) {
	return a.svc.SendTest(ctx, userID)
}
func (a testAdapter) EmailAvailable() bool { return a.svc.EmailAvailable() }
