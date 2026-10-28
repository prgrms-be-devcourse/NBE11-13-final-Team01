package com.zipick.global.error;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    INVALID_INPUT(HttpStatus.BAD_REQUEST, "요청 값이 올바르지 않습니다."),
    AUTH_REQUIRED(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "접근 권한이 없습니다."),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 리소스를 찾을 수 없습니다."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "지원하지 않는 HTTP 메서드입니다."),
    DATA_CONFLICT(HttpStatus.CONFLICT, "요청이 현재 데이터 상태와 충돌합니다."),

    // 인증
    INVALID_LOGIN(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다."),
    INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "유효하지 않은 Refresh Token입니다."),
    EXPIRED_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "만료된 Refresh Token입니다."),
    REFRESH_TOKEN_NOT_FOUND(HttpStatus.UNAUTHORIZED, "Refresh Token이 없습니다."),
    DUPLICATE_EMAIL(HttpStatus.CONFLICT, "이미 사용 중인 이메일입니다."),
    EMAIL_NOT_VERIFIED(HttpStatus.BAD_REQUEST, "이메일 인증을 먼저 완료해 주세요."),
    VERIFICATION_CODE_INVALID(HttpStatus.BAD_REQUEST, "인증 코드가 올바르지 않거나 만료됐습니다."),
    VERIFICATION_TOO_MANY_ATTEMPTS(HttpStatus.TOO_MANY_REQUESTS, "인증 코드를 여러 번 틀렸어요. 코드를 다시 받아 주세요."),
    VERIFICATION_RESEND_TOO_SOON(HttpStatus.TOO_MANY_REQUESTS, "인증 메일을 방금 보냈어요. 잠시 뒤 다시 시도해 주세요."),
    VERIFICATION_DAILY_LIMIT(HttpStatus.TOO_MANY_REQUESTS, "오늘 보낼 수 있는 인증 메일 횟수를 넘었어요. 내일 다시 시도해 주세요."),

    HOUSE_NOT_FOUND(HttpStatus.NOT_FOUND, "공급주택을 찾을 수 없습니다."),
    ANNOUNCEMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "공고를 찾을 수 없습니다."),
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다."),
    PROFILE_NOT_FOUND(HttpStatus.NOT_FOUND, "기본 정보를 먼저 입력해 주세요."),
    PREFERENCE_NOT_FOUND(HttpStatus.NOT_FOUND, "희망 주거조건을 먼저 입력해 주세요."),
    RECOMMENDATION_PROFILE_NOT_FOUND(HttpStatus.NOT_FOUND, "추천 기준을 찾을 수 없습니다."),
    FEEDBACK_NOT_FOUND(HttpStatus.NOT_FOUND, "피드백을 찾을 수 없습니다."),

    PROFILE_LIMIT_EXCEEDED(HttpStatus.CONFLICT, "추천 기준은 최대 3개까지 저장할 수 있습니다."),
    ACTIVE_PROFILE_REQUIRED(HttpStatus.CONFLICT, "사용 중인 추천 기준은 삭제할 수 없습니다."),
    PAIR_ANSWERS_NOT_ENOUGH(HttpStatus.CONFLICT, "두 집 비교 답이 부족합니다."),

    NOTIFICATION_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "알림 서버에 연결할 수 없습니다. 잠시 뒤 다시 시도해 주세요."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다.");

    private final HttpStatus status;
    private final String defaultMessage;
}
