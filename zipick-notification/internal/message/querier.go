package message

import "github.com/jackc/pgx/v5/pgconn"

// pgconnCommandTag 는 pgx 의 Exec 반환 타입. querier 인터페이스에서 이름만 짧게 쓰기 위한 별칭.
type pgconnCommandTag = pgconn.CommandTag
