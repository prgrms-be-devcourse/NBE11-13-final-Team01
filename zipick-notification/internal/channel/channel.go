// Package channel 은 실제로 보내는 채널이다. 새 채널(카카오 알림톡 등)은 Channel 인터페이스를 구현해 목록에 더하면 된다.
// Go 의 인터페이스는 implements 선언 없이, 메서드만 맞으면 자동으로 구현된 것으로 본다.
package channel

import "context"

// Recipient 는 보낼 곳. 구독에서 가져오거나, 요청이 직접 지정한다 (가입 인증처럼 아직 구독이 없는 경우).
type Recipient struct {
	Email           *string
	SlackWebhookURL *string
}

func (r Recipient) IsEmpty() bool { return r.Email == nil && r.SlackWebhookURL == nil }

// Message 는 채널이 알아야 할 최소 정보 (제목·본문·링크). 아웃박스 행 전체를 넘기지 않아 패키지 의존이 생기지 않는다.
type Message struct {
	Title string
	Body  string
	URL   *string
}

// Text 는 본문 + 링크를 한 덩어리로.
func (m Message) Text() string {
	if m.URL == nil {
		return m.Body
	}
	return m.Body + "\n\n" + *m.URL
}

// Channel 은 채널 하나. Send 가 error 를 돌려주면 호출한 쪽이 attempts 를 올리고 다음 주기에 다시 시도한다.
type Channel interface {
	Name() string
	Supports(r Recipient) bool
	Send(ctx context.Context, r Recipient, m Message) error
}
