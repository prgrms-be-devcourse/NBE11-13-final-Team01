package campaign

import (
	"context"
	"errors"
	"strings"
	"testing"

	"github.com/zipick/notification/internal/httpx"
)

func TestUserIDList_쉼표_구분을_숫자로_중복과_잘못된_값은_버림(t *testing.T) {
	s := "3, 1,abc,3,,2"
	got := Campaign{UserIDs: &s}.userIDList()
	if len(got) != 3 || got[0] != 3 || got[1] != 1 || got[2] != 2 {
		t.Fatalf("got %v", got)
	}
	if len((Campaign{}).userIDList()) != 0 {
		t.Fatal("nil 이면 빈 목록")
	}
}

// DB 를 보기 전에 끝나는 검증만 확인한다 (Service 의 의존성은 nil)
func TestCreate_입력_검증(t *testing.T) {
	s := &Service{}
	cases := map[string]Request{
		"이름 없음":           {Name: " ", TemplateCode: "NEWSLETTER", Audience: MarketingConsented},
		"이름 너무 김":         {Name: strings.Repeat("가", 101), TemplateCode: "NEWSLETTER", Audience: MarketingConsented},
		"모르는 대상":          {Name: "x", TemplateCode: "NEWSLETTER", Audience: "EVERYONE"},
		"USER_IDS 인데 비었음": {Name: "x", TemplateCode: "NEWSLETTER", Audience: UserIDs},
	}
	for name, r := range cases {
		_, err := s.Create(context.Background(), r)
		var e *httpx.Error
		if !errors.As(err, &e) || e.Code != httpx.InvalidInput {
			t.Errorf("%s: got %v", name, err)
		}
	}
}
