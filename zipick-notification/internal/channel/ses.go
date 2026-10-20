package channel

import (
	"context"
	"fmt"

	awsconfig "github.com/aws/aws-sdk-go-v2/config"
	"github.com/aws/aws-sdk-go-v2/service/sesv2"
	"github.com/aws/aws-sdk-go-v2/service/sesv2/types"
	"github.com/zipick/notification/internal/config"
)

// SESEmail 은 이메일 ② AWS SES. provider=ses 일 때.
// 자격 증명은 AWS 기본 체인(환경 변수 AWS_ACCESS_KEY_ID/SECRET, 또는 EC2 IAM 역할)에서 읽는다.
// 처음엔 샌드박스라 '인증된 주소'로만 보낼 수 있고, 발신 주소(From)는 SES 콘솔에서 인증돼 있어야 한다.
type SESEmail struct {
	cfg    config.EmailConfig
	client *sesv2.Client
}

func NewSESEmail(ctx context.Context, cfg config.EmailConfig) (*SESEmail, error) {
	if cfg.From == "" {
		return nil, fmt.Errorf("NOTIFICATION_EMAIL_PROVIDER=ses 이면 NOTIFICATION_EMAIL_FROM 이 필요합니다")
	}
	aws, err := awsconfig.LoadDefaultConfig(ctx, awsconfig.WithRegion(cfg.SESRegion))
	if err != nil {
		return nil, fmt.Errorf("AWS 설정 읽기 실패: %w", err)
	}
	return &SESEmail{cfg: cfg, client: sesv2.NewFromConfig(aws)}, nil
}

func (e *SESEmail) Name() string              { return "email" }
func (e *SESEmail) Supports(r Recipient) bool { return r.Email != nil }

func (e *SESEmail) Send(ctx context.Context, r Recipient, m Message) error {
	from := e.cfg.From
	if e.cfg.FromName != "" {
		from = e.cfg.FromName + " <" + e.cfg.From + ">"
	}
	utf8 := func(s string) *types.Content { return &types.Content{Data: &s, Charset: strPtr("UTF-8")} }
	_, err := e.client.SendEmail(ctx, &sesv2.SendEmailInput{
		FromEmailAddress: &from,
		Destination:      &types.Destination{ToAddresses: []string{*r.Email}},
		Content: &types.EmailContent{Simple: &types.Message{
			Subject: utf8(m.Title),
			Body:    &types.Body{Text: utf8(m.Text())},
		}},
	})
	return err
}

func strPtr(s string) *string { return &s }

// Build 는 설정에 따라 채널 목록을 만든다. 슬랙은 항상, 이메일은 provider 에 따라.
func Build(ctx context.Context, cfg config.EmailConfig) ([]Channel, error) {
	channels := []Channel{NewSlack()}
	switch cfg.Provider {
	case "smtp":
		channels = append(channels, NewSMTPEmail(cfg))
	case "ses":
		ses, err := NewSESEmail(ctx, cfg)
		if err != nil {
			return nil, err
		}
		channels = append(channels, ses)
	}
	return channels, nil
}

// HasEmail 은 이메일 채널이 켜져 있는지 (설정 화면의 emailAvailable).
func HasEmail(channels []Channel) bool {
	for _, c := range channels {
		if c.Name() == "email" {
			return true
		}
	}
	return false
}
