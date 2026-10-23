package config

import "testing"

func TestLoad_DefaultsAndTemplatesDir(t *testing.T) {
	t.Setenv("DB_USERNAME", "u")
	t.Setenv("DB_PASSWORD", "p")
	t.Setenv("NOTIFICATION_INTERNAL_KEY", "k")
	cfg, err := Load()
	if err != nil {
		t.Fatal(err)
	}
	if cfg.TemplatesDir != "./templates" || cfg.RetentionDays != 90 || cfg.MaxAttempts != 3 {
		t.Fatalf("%+v", cfg)
	}
	t.Setenv("NOTIFICATION_TEMPLATES_DIR", "/templates")
	t.Setenv("NOTIFICATION_RETENTION_DAYS", "30")
	cfg, _ = Load()
	if cfg.TemplatesDir != "/templates" || cfg.RetentionDays != 30 {
		t.Fatalf("%+v", cfg)
	}
}
