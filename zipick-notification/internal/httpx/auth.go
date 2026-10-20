package httpx

import (
	"crypto/subtle"
	"log/slog"
	"net/http"
	"strings"
)

// HeaderInternalKey 메인 서버가 보내는 헤더 이름.
const HeaderInternalKey = "X-Internal-Key"

// RequireInternalKey 는 /internal/ 로 시작하는 요청에 공유 키를 요구하는 미들웨어.
// Spring 의 OncePerRequestFilter 와 같다. 키가 설정돼 있지 않으면 전부 거부한다 (열린 채로 뜨는 일이 없게).
// subtle.ConstantTimeCompare 는 문자열 비교 시간으로 키를 추측하는 공격을 막는다.
func RequireInternalKey(key string, next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if !strings.HasPrefix(r.URL.Path, "/internal/") {
			next.ServeHTTP(w, r)
			return
		}
		given := r.Header.Get(HeaderInternalKey)
		if key != "" && given != "" && subtle.ConstantTimeCompare([]byte(given), []byte(key)) == 1 {
			next.ServeHTTP(w, r)
			return
		}
		if key == "" {
			slog.Warn("NOTIFICATION_INTERNAL_KEY 가 비어 있어 /internal 요청을 모두 거부합니다")
		}
		WriteError(w, Fail(AuthRequired, "내부 키가 없거나 올바르지 않습니다."))
	})
}
