// Package config 는 환경 변수를 읽어 설정 구조체로 만든다.
// Spring 의 application.yml + @ConfigurationProperties 역할. 기본값은 여기 한 곳에만 둔다.
package config

import (
	"fmt"
	"os"
	"strconv"
	"strings"
	"time"
)

// Config 는 서버 전체 설정. main.go 가 한 번 만들어 필요한 패키지에 넘긴다.
type Config struct {
	Port        string        // 서버 포트 (NOTIFICATION_PORT, 기본 8081)
	InternalKey string        // 메인 서버 ↔ 알림센터 공유 키 (NOTIFICATION_INTERNAL_KEY). 비어 있으면 /internal 전부 거부
	AppURL      string        // 알림 링크가 열릴 서비스 주소. 템플릿 {{appUrl}} (APP_ORIGIN)
	DBURL       string        // postgres://user:pass@host:port/db
	PollEvery   time.Duration // 스케줄러 주기 (NOTIFICATION_POLL_INTERVAL, 기본 1m)
	MaxAttempts int           // 발송 실패 재시도 횟수 (기본 3)
	Scheduler   bool          // false 면 스케줄러를 띄우지 않음 (테스트용)
	QuietStart  string        // 광고 발송 금지 시작 "21:00"
	QuietEnd    string        // 광고 발송 금지 끝 "08:00"

	Email EmailConfig
}

// EmailConfig 이메일 채널 설정. Provider 가 none 이면 이메일 채널이 꺼진다 (슬랙만 동작).
type EmailConfig struct {
	Provider  string // none | smtp | ses (NOTIFICATION_EMAIL_PROVIDER)
	From      string // 발신 주소 (NOTIFICATION_EMAIL_FROM)
	FromName  string // 발신 이름 (기본 ZIPICK)
	SESRegion string // AWS_REGION (기본 ap-northeast-2)
	SMTPHost  string // MAIL_HOST
	SMTPPort  string // MAIL_PORT (기본 587)
	SMTPUser  string // MAIL_USERNAME
	SMTPPass  string // MAIL_PASSWORD
}

// Load 는 환경 변수를 읽는다. 필수 값이 없으면 error 를 돌려준다.
// Go 에는 예외가 없다. 실패할 수 있는 함수는 마지막 반환값으로 error 를 주고, 호출한 쪽이 if err != nil 로 확인한다.
func Load() (Config, error) {
	c := Config{
		Port:        env("NOTIFICATION_PORT", "8081"),
		InternalKey: os.Getenv("NOTIFICATION_INTERNAL_KEY"),
		AppURL:      env("APP_ORIGIN", "http://localhost:5173"),
		PollEvery:   envDuration("NOTIFICATION_POLL_INTERVAL", time.Minute),
		MaxAttempts: envInt("NOTIFICATION_MAX_ATTEMPTS", 3),
		Scheduler:   env("NOTIFICATION_SCHEDULER_ENABLED", "true") == "true",
		QuietStart:  env("NOTIFICATION_QUIET_START", "21:00"),
		QuietEnd:    env("NOTIFICATION_QUIET_END", "08:00"),
		Email: EmailConfig{
			Provider:  strings.ToLower(env("NOTIFICATION_EMAIL_PROVIDER", "none")),
			From:      os.Getenv("NOTIFICATION_EMAIL_FROM"),
			FromName:  env("NOTIFICATION_EMAIL_FROM_NAME", "ZIPICK"),
			SESRegion: env("AWS_REGION", "ap-northeast-2"),
			SMTPHost:  os.Getenv("MAIL_HOST"),
			SMTPPort:  env("MAIL_PORT", "587"),
			SMTPUser:  os.Getenv("MAIL_USERNAME"),
			SMTPPass:  os.Getenv("MAIL_PASSWORD"),
		},
	}

	user, pass := os.Getenv("DB_USERNAME"), os.Getenv("DB_PASSWORD")
	if user == "" || pass == "" {
		return c, fmt.Errorf("DB_USERNAME, DB_PASSWORD 환경 변수가 필요합니다")
	}
	c.DBURL = fmt.Sprintf("postgres://%s:%s@%s:%s/%s?pool_max_conns=10",
		user, pass, env("DB_HOST", "localhost"), env("DB_PORT", "5432"), env("DB_NAME", "zipick_notification"))

	if c.Email.Provider == "smtp" && c.Email.SMTPHost == "" {
		return c, fmt.Errorf("NOTIFICATION_EMAIL_PROVIDER=smtp 이면 MAIL_HOST 가 필요합니다")
	}
	return c, nil
}

func env(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}

func envInt(key string, def int) int {
	if v, err := strconv.Atoi(os.Getenv(key)); err == nil && v > 0 {
		return v
	}
	return def
}

// envDuration 은 "1m", "30s" 같은 Go 표기와 "PT1M" 같은 ISO-8601(Spring 설정과 호환) 둘 다 받는다.
func envDuration(key string, def time.Duration) time.Duration {
	v := os.Getenv(key)
	if v == "" {
		return def
	}
	if strings.HasPrefix(v, "PT") {
		v = strings.ToLower(strings.TrimPrefix(v, "PT"))
	}
	if d, err := time.ParseDuration(v); err == nil && d > 0 {
		return d
	}
	return def
}
