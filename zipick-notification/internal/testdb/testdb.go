// Package testdb 는 SQL 을 실제로 확인하는 통합 테스트용 도우미다.
//
// NOTIFICATION_TEST_DB_URL 이 없으면 테스트를 건너뛴다 (CI·다른 PC 에서 실패하지 않게).
// 로컬에서 돌리려면 빈 DB 를 하나 만들고 db/init.sql 을 적용한 뒤 주소를 넣는다:
//
//	docker exec zipick-dev-postgres psql -U zipick -d postgres -c "CREATE DATABASE zipick_notification_test;"
//	docker exec -i zipick-dev-postgres psql -U zipick -d zipick_notification_test < db/init.sql
//	NOTIFICATION_TEST_DB_URL=postgres://zipick:<비밀번호>@localhost:5432/zipick_notification_test go test ./...
//
// 각 테스트 시작 때 모든 테이블을 비운다. 운영·개발 DB 주소를 넣지 않도록 이름이 _test 로 끝나야만 동작한다.
package testdb

import (
	"context"
	"os"
	"strings"
	"testing"

	"github.com/jackc/pgx/v5/pgxpool"
)

func Pool(t *testing.T) *pgxpool.Pool {
	t.Helper()
	url := os.Getenv("NOTIFICATION_TEST_DB_URL")
	if url == "" {
		t.Skip("NOTIFICATION_TEST_DB_URL 이 없어 DB 통합 테스트를 건너뜀")
	}
	if !strings.Contains(url, "_test") {
		t.Fatal("테스트 DB 이름은 _test 로 끝나야 합니다 (테이블을 비우기 때문)")
	}
	pool, err := pgxpool.New(context.Background(), url)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(pool.Close)
	if _, err := pool.Exec(context.Background(),
		`truncate notification_messages, campaigns, subscriptions, notification_templates restart identity`); err != nil {
		t.Fatal(err)
	}
	return pool
}
