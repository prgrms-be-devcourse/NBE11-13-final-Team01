package config

import (
	"strings"
	"testing"
	"time"
)

// t.Setenv 는 테스트가 끝나면 원래 값으로 되돌린다
func baseEnv(t *testing.T) {
	t.Setenv("DB_USERNAME", "zipick")
	t.Setenv("DB_PASSWORD", "pw")
}

func TestLoad_기본값(t *testing.T) {
	baseEnv(t)
	c, err := Load()
	if err != nil {
		t.Fatal(err)
	}
	if c.Port != "8081" || c.PollEvery != time.Minute || c.MaxAttempts != 3 || !c.Scheduler || c.Email.Provider != "none" || c.RabbitURL != "" {
		t.Fatalf("defaults: %+v", c)
	}
	if !strings.HasSuffix(c.DBURL, "@localhost:5432/zipick_notification?pool_max_conns=10") {
		t.Fatalf("db url: %s", c.DBURL)
	}
}

func TestLoad_DB_계정이_없으면_실패(t *testing.T) {
	t.Setenv("DB_USERNAME", "")
	t.Setenv("DB_PASSWORD", "")
	if _, err := Load(); err == nil {
		t.Fatal("DB 계정 없이 통과함")
	}
}

func TestLoad_SMTP_를_고르면_호스트가_필요(t *testing.T) {
	baseEnv(t)
	t.Setenv("NOTIFICATION_EMAIL_PROVIDER", "SMTP")
	if _, err := Load(); err == nil {
		t.Fatal("MAIL_HOST 없이 통과함")
	}
	t.Setenv("MAIL_HOST", "smtp.gmail.com")
	c, err := Load()
	if err != nil || c.Email.Provider != "smtp" {
		t.Fatalf("%v %+v", err, c.Email)
	}
}

func TestLoad_주기는_Go_표기와_Spring_ISO_표기를_모두_받는다(t *testing.T) {
	baseEnv(t)
	for in, want := range map[string]time.Duration{"30s": 30 * time.Second, "PT2M": 2 * time.Minute, "엉터리": time.Minute, "-5s": time.Minute} {
		t.Setenv("NOTIFICATION_POLL_INTERVAL", in)
		c, _ := Load()
		if c.PollEvery != want {
			t.Errorf("%s → %v, want %v", in, c.PollEvery, want)
		}
	}
}

func TestLoad_숫자_설정이_잘못되면_기본값(t *testing.T) {
	baseEnv(t)
	t.Setenv("NOTIFICATION_MAX_ATTEMPTS", "abc")
	t.Setenv("NOTIFICATION_SCHEDULER_ENABLED", "false")
	t.Setenv("RABBITMQ_URL", "amqp://u:p@rabbitmq:5672/")
	c, _ := Load()
	if c.MaxAttempts != 3 || c.Scheduler || c.RabbitURL == "" {
		t.Fatalf("%+v", c)
	}
}
