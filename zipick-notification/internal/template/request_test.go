package template

import (
	"strings"
	"testing"
)

func TestRequest_Validate(t *testing.T) {
	ok := Request{Name: "마감", Category: Informational, TitleTemplate: "t", BodyTemplate: "b"}
	if ok.Validate() != nil {
		t.Fatal("정상 요청 거부")
	}
	long := strings.Repeat("u", 501)
	bad := map[string]Request{
		"이름 없음":    {Category: Informational, TitleTemplate: "t", BodyTemplate: "b"},
		"모르는 카테고리": {Name: "x", Category: "PROMO", TitleTemplate: "t", BodyTemplate: "b"},
		"제목 없음":    {Name: "x", Category: Marketing, BodyTemplate: "b"},
		"본문 없음":    {Name: "x", Category: Marketing, TitleTemplate: "t", BodyTemplate: "  "},
		"링크 너무 김":  {Name: "x", Category: Marketing, TitleTemplate: "t", BodyTemplate: "b", URLTemplate: &long},
	}
	for name, r := range bad {
		if r.Validate() == nil {
			t.Errorf("%s: 통과시킴", name)
		}
	}
}

func TestWithVariables_응답에_변수_목록을_채운다(t *testing.T) {
	url := "{{appUrl}}/x"
	got := Template{Code: "T", TitleTemplate: "{{a}}", BodyTemplate: "{{b}} {{a}}", URLTemplate: &url}.WithVariables()
	if strings.Join(got.Variables, ",") != "a,appUrl,b" {
		t.Fatalf("%v", got.Variables)
	}
	if v := (Template{TitleTemplate: "고정", BodyTemplate: "고정"}).WithVariables().Variables; v == nil || len(v) != 0 {
		t.Fatal("변수가 없으면 빈 목록 [] (null 아님)")
	}
	if (Template{Code: "T", Category: Marketing}).String() != "Template{T MARKETING}" {
		t.Fatal("String")
	}
}

func TestRender_변수_맵이_nil_이어도_동작(t *testing.T) {
	r := Render("{{a}}!", nil)
	if r.Text != "!" || len(r.Missing) != 1 {
		t.Fatalf("%+v", r)
	}
}
