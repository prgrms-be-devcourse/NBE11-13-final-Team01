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

    HOUSE_NOT_FOUND(HttpStatus.NOT_FOUND, "공급주택을 찾을 수 없습니다."),
    ANNOUNCEMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "공고를 찾을 수 없습니다."),
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다."),
    PROFILE_NOT_FOUND(HttpStatus.NOT_FOUND, "기본 정보를 먼저 입력해 주세요."),
    PREFERENCE_NOT_FOUND(HttpStatus.NOT_FOUND, "희망 주거조건을 먼저 입력해 주세요."),
    RECOMMENDATION_PROFILE_NOT_FOUND(HttpStatus.NOT_FOUND, "추천 기준을 찾을 수 없습니다."),
    FEEDBACK_NOT_FOUND(HttpStatus.NOT_FOUND, "피드백을 찾을 수 없습니다."),

    PROFILE_LIMIT_EXCEEDED(HttpStatus.CONFLICT, "추천 기준은 최대 3개까지 저장할 수 있습니다."),
    ACTIVE_PROFILE_REQUIRED(HttpStatus.CONFLICT, "사용 중인 추천 기준은 삭제할 수 없습니다."),

    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다.");

    private final HttpStatus status;
    private final String defaultMessage;
}
