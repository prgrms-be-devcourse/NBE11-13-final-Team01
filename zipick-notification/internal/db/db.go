// Package db 는 PostgreSQL 연결 풀과, Java 의 LocalDateTime 과 호환되는 시각 타입을 제공한다.
package db

import (
	"context"
	"fmt"

	"github.com/jackc/pgx/v5/pgxpool"
)

// Connect 는 연결 풀을 만든다. pgxpool 은 HikariCP 처럼 연결을 재사용한다.
// context 는 Go 에서 취소·타임아웃을 전달하는 표준 방식이다 (거의 모든 I/O 함수의 첫 인자).
func Connect(ctx context.Context, url string) (*pgxpool.Pool, error) {
	pool, err := pgxpool.New(ctx, url)
	if err != nil {
		return nil, fmt.Errorf("DB 연결 설정 실패: %w", err)
	}
	if err := pool.Ping(ctx); err != nil {
		return nil, fmt.Errorf("DB 연결 실패: %w", err)
	}
	return pool, nil
}
