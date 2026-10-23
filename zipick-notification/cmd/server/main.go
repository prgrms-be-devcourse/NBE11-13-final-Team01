// 알림센터 진입점. 설정 → DB → 템플릿 → 채널 → 서비스 → 라우터 → 스케줄러·큐 순으로 조립한다.
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

	"github.com/zipick/notification/internal/channel"
	"github.com/zipick/notification/internal/config"
	"github.com/zipick/notification/internal/db"
	"github.com/zipick/notification/internal/httpx"
	"github.com/zipick/notification/internal/message"
	"github.com/zipick/notification/internal/queue"
	"github.com/zipick/notification/internal/scheduler"
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

	// 템플릿은 파일에서 한 번 읽는다. 잘못된 파일이 있으면 여기서 바로 실패한다
	templates, err := template.Load(cfg.TemplatesDir)
	if err != nil {
		return err
	}
	channels, err := channel.Build(ctx, cfg.Email)
	if err != nil {
		return err
	}

	svc := message.NewService(message.NewRepo(pool), templates, channels, cfg)

	mux := http.NewServeMux()
	mux.HandleFunc("GET /health", func(w http.ResponseWriter, _ *http.Request) { httpx.OK(w, map[string]string{"status": "UP"}) })
	template.NewHandler(templates).Register(mux)
	message.NewHandler(svc).Register(mux)

	if cfg.Scheduler {
		go scheduler.Run(ctx, cfg.PollEvery, 15*time.Second, svc)
	}
	// 큐 리스너: 요청하는 서버가 던진 알림 요청을 받는다. RABBITMQ_URL 이 없으면 HTTP 로만 받는다
	if cfg.RabbitURL != "" {
		go queue.Run(ctx, cfg.RabbitURL, svc)
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
	slog.Info("알림센터 시작", "port", cfg.Port, "templates", len(templates.All()), "email", cfg.Email.Provider,
		"scheduler", cfg.Scheduler, "keySet", cfg.InternalKey != "", "queue", cfg.RabbitURL != "")
	if err := server.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
		return err
	}
	return nil
}
