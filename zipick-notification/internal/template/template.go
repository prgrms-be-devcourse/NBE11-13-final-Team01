// Package template 은 메시지 템플릿을 다룬다.
// 본문에 {{변수}} 자리를 두고, 요청이 보낸 변수로 채운다. 다른 서버는 문구를 모른다 — 문구를 바꾸려면 여기(DB)만 바꾼다.
package template

import (
	"context"
	"errors"
	"fmt"
	"strings"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/zipick/notification/internal/db"
	"github.com/zipick/notification/internal/httpx"
)

// Category 는 메시지 성격. 누구에게 보낼 수 있는지가 여기서 갈린다.
//
//	TRANSACTIONAL : 가입 인증·비밀번호 변경처럼 요청한 사람에게 꼭 가야 하는 메시지. 동의와 무관하게 보낸다.
//	INFORMATIONAL : 마감 알림·새 공고처럼 서비스 이용 중 받기로 한 알림. 알림 설정(enabled)이 켜진 사용자에게만.
//	MARKETING     : 광고·홍보. 광고 수신 동의자에게만, 제목 앞 "(광고)", 21~08시 발송 금지.
type Category string

const (
	Transactional Category = "TRANSACTIONAL"
	Informational Category = "INFORMATIONAL"
	Marketing     Category = "MARKETING"
)

func (c Category) valid() bool {
	return c == Transactional || c == Informational || c == Marketing
}

// Template 은 notification_templates 한 행. Go 의 struct 는 Java 의 record 와 비슷하다 (필드만 있고 메서드는 따로).
// `json:"..."` 태그가 JSON 키 이름을 정한다 (Jackson 이 camelCase 로 쓰는 것과 맞춤).
type Template struct {
	Code          string   `json:"code"`
	Name          string   `json:"name"`
	Category      Category `json:"category"`
	TitleTemplate string   `json:"titleTemplate"`
	BodyTemplate  string   `json:"bodyTemplate"`
	URLTemplate   *string  `json:"urlTemplate"` // 포인터 = null 가능. Java 의 String 이 null 일 수 있는 것과 같다
	Description   *string  `json:"description"`
	Enabled       bool     `json:"enabled"`
	Variables     []string `json:"variables"` // 템플릿이 쓰는 변수 이름 (응답에만 포함, DB 에는 없음)
}

// Repo 는 DB 조회 묶음. Spring 의 Repository 인터페이스 + 구현에 해당한다.
// Go 에서는 ORM 없이 SQL 을 직접 쓰는 편이 흔하다. 쿼리가 눈에 보여서 성능 추적이 쉽다.
type Repo struct{ pool *pgxpool.Pool }

func NewRepo(pool *pgxpool.Pool) *Repo { return &Repo{pool: pool} }

const selectCols = `code, name, category, title_template, body_template, url_template, description, enabled`

func scan(row pgx.Row) (Template, error) {
	var t Template
	err := row.Scan(&t.Code, &t.Name, &t.Category, &t.TitleTemplate, &t.BodyTemplate, &t.URLTemplate, &t.Description, &t.Enabled)
	return t, err
}

// FindByCode 는 없으면 httpx.TemplateNotFound 오류를 돌려준다.
func (r *Repo) FindByCode(ctx context.Context, code string) (Template, error) {
	t, err := scan(r.pool.QueryRow(ctx, `select `+selectCols+` from notification_templates where code = $1`, code))
	if errors.Is(err, pgx.ErrNoRows) {
		return t, httpx.Fail(httpx.TemplateNotFound, "템플릿이 없습니다: "+code)
	}
	return t, err
}

func (r *Repo) FindAll(ctx context.Context) ([]Template, error) {
	rows, err := r.pool.Query(ctx, `select `+selectCols+` from notification_templates order by id`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Template
	for rows.Next() {
		t, err := scan(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, t)
	}
	return out, rows.Err()
}

// Upsert 는 있으면 고치고 없으면 만든다 (운영자가 문구를 바꾸는 유일한 길).
// PostgreSQL 의 ON CONFLICT 로 한 번에 처리한다.
func (r *Repo) Upsert(ctx context.Context, t Template) error {
	_, err := r.pool.Exec(ctx, `
		insert into notification_templates (code, name, category, title_template, body_template, url_template, description, enabled, created_at, updated_at)
		values ($1, $2, $3, $4, $5, $6, $7, $8, $9, $9)
		on conflict (code) do update set
		  name = excluded.name, category = excluded.category, title_template = excluded.title_template,
		  body_template = excluded.body_template, url_template = excluded.url_template,
		  description = excluded.description, enabled = excluded.enabled, updated_at = excluded.updated_at`,
		t.Code, t.Name, t.Category, t.TitleTemplate, t.BodyTemplate, t.URLTemplate, t.Description, t.Enabled, db.Now())
	return err
}

// InsertIfMissing 은 기본 템플릿 시더용. 이미 있으면 아무것도 하지 않는다 (운영자가 고친 문구를 덮어쓰지 않게).
func (r *Repo) InsertIfMissing(ctx context.Context, t Template) (bool, error) {
	tag, err := r.pool.Exec(ctx, `
		insert into notification_templates (code, name, category, title_template, body_template, url_template, description, enabled, created_at, updated_at)
		values ($1, $2, $3, $4, $5, $6, $7, $8, $9, $9) on conflict (code) do nothing`,
		t.Code, t.Name, t.Category, t.TitleTemplate, t.BodyTemplate, t.URLTemplate, t.Description, t.Enabled, db.Now())
	return tag.RowsAffected() == 1, err
}

// Request 는 PUT /internal/templates/{code} 본문.
type Request struct {
	Name          string   `json:"name"`
	Category      Category `json:"category"`
	TitleTemplate string   `json:"titleTemplate"`
	BodyTemplate  string   `json:"bodyTemplate"`
	URLTemplate   *string  `json:"urlTemplate"`
	Description   *string  `json:"description"`
	Enabled       bool     `json:"enabled"`
}

// Validate 는 Spring 의 @NotBlank/@Size 검증에 해당한다. Go 는 어노테이션이 없어 직접 적는다.
func (r Request) Validate() error {
	switch {
	case strings.TrimSpace(r.Name) == "" || len(r.Name) > 100:
		return httpx.Fail(httpx.InvalidInput, "name 은 1~100자여야 합니다.")
	case !r.Category.valid():
		return httpx.Fail(httpx.InvalidInput, "category 는 TRANSACTIONAL, INFORMATIONAL, MARKETING 중 하나여야 합니다.")
	case strings.TrimSpace(r.TitleTemplate) == "" || len(r.TitleTemplate) > 300:
		return httpx.Fail(httpx.InvalidInput, "titleTemplate 은 1~300자여야 합니다.")
	case strings.TrimSpace(r.BodyTemplate) == "":
		return httpx.Fail(httpx.InvalidInput, "bodyTemplate 은 비울 수 없습니다.")
	case r.URLTemplate != nil && len(*r.URLTemplate) > 500:
		return httpx.Fail(httpx.InvalidInput, "urlTemplate 은 500자 이하여야 합니다.")
	}
	return nil
}

// String 은 디버그 로그용.
func (t Template) String() string { return fmt.Sprintf("Template{%s %s}", t.Code, t.Category) }
