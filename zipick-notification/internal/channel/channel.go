// Package channel 은 실제로 보내는 채널이다. 새 채널(카카오 알림톡 등)은 Channel 인터페이스를 구현해 목록에 더하면 된다.
// Go 의 인터페이스는 implements 선언 없이, 메서드만 맞으면 자동으로 구현된 것으로 본다.
//
// 채널은 판단하지 않는다: 받은 주소로, 받은 문구를, 보낼 뿐이다.
package channel

import "context"

// Recipient 는 보낼 곳. 요청이 직접 지정한다 (알림센터는 사용자를 모른다).
type Recipient struct {
	Email           *string
	SlackWebhookURL *string
}

// Content 는 이미 채워진 문구. 이메일은 Subject+Body, 슬랙은 Body 만 쓴다.
type Content struct {
	Subject string
	Body    string
}

// Channel 은 채널 하나. Send 가 error 를 돌려주면 호출한 쪽이 attempts 를 올리고 다음 주기에 다시 시도한다.
type Channel interface {
	Name() string
	// HasAddress 는 받는 쪽에 이 채널의 주소가 있는지 (요청 검사용)
	HasAddress(r Recipient) bool
	Send(ctx context.Context, r Recipient, c Content) error
}

// ByName 은 이름 → 채널. 요청 검사와 발송에서 쓴다.
func ByName(channels []Channel) map[string]Channel {
	m := make(map[string]Channel, len(channels))
	for _, c := range channels {
		m[c.Name()] = c
	}
	return m
}

// Available 은 채널 이름 → 켜져 있는지. GET /internal/v1/channels 응답.
func Available(channels []Channel) map[string]bool {
	m := map[string]bool{"email": false, "slack": false}
	for _, c := range channels {
		m[c.Name()] = true
	}
	return m
}
