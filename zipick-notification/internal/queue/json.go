package queue

import "encoding/json"

// unmarshal 은 테스트에서도 같은 해석기를 쓰기 위한 작은 포장
func unmarshal(b []byte, v any) error { return json.Unmarshal(b, v) }
