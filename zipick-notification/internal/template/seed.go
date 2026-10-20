package template

import (
	"context"
	"log/slog"
)

func str(s string) *string { return &s }

// Seed 는 기본 템플릿을 넣는다. 코드가 없을 때만 — 운영자가 고친 문구를 재시작이 덮어쓰지 않게.
// 공통 변수 {{appUrl}} (서비스 주소)는 알림센터가 항상 채운다.
func Seed(ctx context.Context, repo *Repo) error {
	defaults := []Template{
		{Code: "TEST", Name: "테스트 알림", Category: Transactional,
			TitleTemplate: "ZIPICK 알림 테스트",
			BodyTemplate:  "알림이 잘 도착했어요. 관심 공고의 접수 마감 {{deadlineDays}}일 전과, 내 조건에 맞는 새 공고가 들어올 때 알려 드려요.",
			URLTemplate:   str("{{appUrl}}/calendar"), Description: str("설정 화면의 '테스트 발송'"), Enabled: true},
		{Code: "DEADLINE", Name: "관심 공고 접수 마감", Category: Informational,
			TitleTemplate: "접수 마감 {{dDayLabel}} · {{noticeTitle}}",
			BodyTemplate:  "관심 공고의 접수가 {{whenText}}({{applyEndAt}}) 마감돼요.{{regionsText}}",
			URLTemplate:   str("{{detailUrl}}"), Description: str("변수: dDayLabel(D-3), whenText(3일 뒤), applyEndAt, noticeTitle, regionsText, detailUrl"), Enabled: true},
		{Code: "NEW_MATCH", Name: "내 조건에 맞는 새 공고", Category: Informational,
			TitleTemplate: "내 조건에 맞는 새 공고 · {{noticeTitle}}",
			BodyTemplate:  "추천도 {{score}}점 · {{region}}{{deadlineText}}",
			URLTemplate:   str("{{detailUrl}}"), Description: str("변수: noticeTitle, score, region, deadlineText, detailUrl"), Enabled: true},
		{Code: "EMAIL_VERIFY", Name: "회원가입 이메일 인증", Category: Transactional,
			TitleTemplate: "[ZIPICK] 이메일 인증 코드: {{code}}",
			BodyTemplate:  "ZIPICK 가입을 위한 인증 코드는 {{code}} 입니다. {{expiresInMinutes}}분 안에 입력해 주세요.\n본인이 요청하지 않았다면 이 메일을 무시하셔도 됩니다.",
			Description:   str("인증 파트가 호출. 변수: code, expiresInMinutes"), Enabled: true},
		{Code: "PASSWORD_CHANGED", Name: "비밀번호 변경 안내", Category: Transactional,
			TitleTemplate: "[ZIPICK] 비밀번호가 변경되었습니다",
			BodyTemplate:  "{{changedAt}}에 비밀번호가 변경되었습니다. 본인이 바꾼 게 아니라면 바로 비밀번호를 다시 설정하고 고객센터에 알려 주세요.",
			URLTemplate:   str("{{appUrl}}"), Description: str("인증 파트가 호출. 변수: changedAt"), Enabled: true},
		{Code: "NEWSLETTER", Name: "소식 메일(광고)", Category: Marketing,
			TitleTemplate: "{{headline}}",
			BodyTemplate:  "{{message}}\n\n수신 거부: {{appUrl}}/calendar 의 알림 설정에서 '광고성 정보 수신'을 끄면 더 보내지 않아요.",
			URLTemplate:   str("{{appUrl}}"), Description: str("운영자 캠페인용. 변수: headline, message. 제목 앞 (광고)는 자동으로 붙는다"), Enabled: true},
	}
	added := 0
	for _, t := range defaults {
		ok, err := repo.InsertIfMissing(ctx, t)
		if err != nil {
			return err
		}
		if ok {
			added++
		}
	}
	if added > 0 {
		slog.Info("기본 템플릿을 넣었습니다", "count", added)
	}
	return nil
}
