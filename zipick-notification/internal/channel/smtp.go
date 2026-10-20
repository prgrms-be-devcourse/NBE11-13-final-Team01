package channel

import (
	"context"
	"encoding/base64"
	"fmt"
	"net/smtp"
	"strings"

	"github.com/zipick/notification/internal/config"
)

// SMTPEmail 은 이메일 ① SMTP (Gmail 앱 비밀번호 등). provider=smtp 일 때 켜진다.
// 표준 라이브러리 net/smtp 는 서버가 STARTTLS 를 지원하면 자동으로 암호화한다.
type SMTPEmail struct{ cfg config.EmailConfig }

func NewSMTPEmail(cfg config.EmailConfig) *SMTPEmail { return &SMTPEmail{cfg: cfg} }

func (e *SMTPEmail) Name() string              { return "email" }
func (e *SMTPEmail) Supports(r Recipient) bool { return r.Email != nil }

func (e *SMTPEmail) Send(_ context.Context, r Recipient, m Message) error {
	from := e.cfg.From
	if from == "" {
		from = e.cfg.SMTPUser
	}
	var auth smtp.Auth
	if e.cfg.SMTPUser != "" {
		auth = smtp.PlainAuth("", e.cfg.SMTPUser, e.cfg.SMTPPass, e.cfg.SMTPHost)
	}
	msg := buildMail(from, e.cfg.FromName, *r.Email, m.Title, m.Text())
	return smtp.SendMail(e.cfg.SMTPHost+":"+e.cfg.SMTPPort, auth, from, []string{*r.Email}, msg)
}

// buildMail 은 RFC 5322 형식의 메일 원문을 만든다. 한글 제목은 base64 로 감싼다 (=?UTF-8?B?...?=).
func buildMail(from, fromName, to, subject, text string) []byte {
	fromHeader := from
	if fromName != "" {
		fromHeader = fmt.Sprintf("=?UTF-8?B?%s?= <%s>", base64.StdEncoding.EncodeToString([]byte(fromName)), from)
	}
	var b strings.Builder
	fmt.Fprintf(&b, "From: %s\r\n", fromHeader)
	fmt.Fprintf(&b, "To: %s\r\n", to)
	fmt.Fprintf(&b, "Subject: =?UTF-8?B?%s?=\r\n", base64.StdEncoding.EncodeToString([]byte(subject)))
	b.WriteString("MIME-Version: 1.0\r\nContent-Type: text/plain; charset=UTF-8\r\nContent-Transfer-Encoding: base64\r\n\r\n")
	b.WriteString(base64.StdEncoding.EncodeToString([]byte(text)))
	b.WriteString("\r\n")
	return []byte(b.String())
}
