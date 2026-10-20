// Package httpx 는 HTTP 공통: 응답 형식, 오류 코드, JSON 읽기/쓰기, 내부 키 검사.
// 메인 서버(Spring)와 같은 응답 모양을 유지해서 NotificationClient 가 그대로 동작한다.
//
//	{ "success": true,  "data": {...}, "error": null }
//	{ "success": false, "data": null,  "error": { "code": "...", "message": "...", "fields": null } }
package httpx

import (
	"encoding/json"
	"errors"
	"log/slog"
	"net/http"
)

// Code 는 오류 코드. Spring 의 ErrorCode enum 에 해당한다.
type Code string

const (
	InvalidInput         Code = "INVALID_INPUT"
	AuthRequired         Code = "AUTH_REQUIRED"
	ResourceNotFound     Code = "RESOURCE_NOT_FOUND"
	TemplateNotFound     Code = "TEMPLATE_NOT_FOUND"
	SubscriptionNotFound Code = "SUBSCRIPTION_NOT_FOUND"
	CampaignNotFound     Code = "CAMPAIGN_NOT_FOUND"
	NoRecipient          Code = "NO_RECIPIENT"
	NoChannel            Code = "NO_CHANNEL"
	InternalError        Code = "INTERNAL_ERROR"
)

// HTTP 상태는 코드마다 정해져 있다.
var statusOf = map[Code]int{
	InvalidInput: 400, AuthRequired: 401, ResourceNotFound: 404, TemplateNotFound: 404,
	SubscriptionNotFound: 404, CampaignNotFound: 404, NoRecipient: 400, NoChannel: 400, InternalError: 500,
}

// Error 는 "사용자에게 보여 줄 수 있는" 오류. Spring 의 BusinessException.
// 서비스 함수가 이 타입을 돌려주면 핸들러가 코드에 맞는 상태와 본문으로 바꿔 준다.
type Error struct {
	Code    Code
	Message string
}

func (e *Error) Error() string { return string(e.Code) + ": " + e.Message }

// Fail 은 오류를 만든다. 예: return httpx.Fail(httpx.InvalidInput, "슬랙 웹훅 주소는 ...")
func Fail(code Code, msg string) error { return &Error{Code: code, Message: msg} }

type envelope struct {
	Success bool       `json:"success"`
	Data    any        `json:"data"`
	Error   *errorBody `json:"error"`
}

type errorBody struct {
	Code    Code              `json:"code"`
	Message string            `json:"message"`
	Fields  map[string]string `json:"fields"`
}

// OK 는 성공 응답을 쓴다. data 가 nil 이면 "data": null.
func OK(w http.ResponseWriter, data any) {
	writeJSON(w, 200, envelope{Success: true, Data: data})
}

// WriteError 는 error 를 응답으로 바꾼다.
// *Error 면 그 코드대로, 아니면(프로그램 오류) 500 으로 보내고 로그에 남긴다.
func WriteError(w http.ResponseWriter, err error) {
	var e *Error
	if errors.As(err, &e) {
		writeJSON(w, statusOf[e.Code], envelope{Error: &errorBody{Code: e.Code, Message: e.Message}})
		return
	}
	slog.Error("unhandled error", "err", err)
	writeJSON(w, 500, envelope{Error: &errorBody{Code: InternalError, Message: "서버 오류가 발생했습니다."}})
}

// Decode 는 요청 본문 JSON 을 v 에 읽는다. 모르는 필드는 무시한다 (Spring 기본과 같음).
func Decode(r *http.Request, v any) error {
	if err := json.NewDecoder(r.Body).Decode(v); err != nil {
		return Fail(InvalidInput, "요청 본문(JSON)을 읽을 수 없습니다.")
	}
	return nil
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

// Handler 는 error 를 돌려주는 핸들러를 표준 http.HandlerFunc 로 감싼다.
// 핸들러마다 오류 처리를 반복하지 않게 하는 작은 장치 (Spring 의 @RestControllerAdvice 역할).
func Handler(f func(w http.ResponseWriter, r *http.Request) error) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if err := f(w, r); err != nil {
			WriteError(w, err)
		}
	}
}
