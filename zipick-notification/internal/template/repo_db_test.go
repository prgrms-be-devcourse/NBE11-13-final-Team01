package template

import (
	"context"
	"testing"

	"github.com/zipick/notification/internal/testdb"
)

func TestSeed_은_있는_템플릿을_덮어쓰지_않고_Upsert_는_고친다(t *testing.T) {
	repo := NewRepo(testdb.Pool(t))
	ctx := context.Background()
	if err := Seed(ctx, repo); err != nil {
		t.Fatal(err)
	}
	all, _ := repo.FindAll(ctx)
	if len(all) != 6 {
		t.Fatalf("기본 템플릿 %d개", len(all))
	}

	edited := all[0]
	edited.TitleTemplate = "운영자가 고친 제목"
	if err := repo.Upsert(ctx, edited); err != nil {
		t.Fatal(err)
	}
	_ = Seed(ctx, repo) // 재시작
	got, _ := repo.FindByCode(ctx, edited.Code)
	if got.TitleTemplate != "운영자가 고친 제목" {
		t.Fatal("재시작이 운영자 수정을 덮어씀")
	}
	if _, err := repo.FindByCode(ctx, "NOPE"); err == nil {
		t.Fatal("없는 코드")
	}
}
