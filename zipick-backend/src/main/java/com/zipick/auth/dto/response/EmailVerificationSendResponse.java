package com.zipick.auth.dto.response;

/**
 * @param required           false 면 이 서버는 이메일 인증 없이 가입할 수 있다 (로컬 개발). 프론트는 코드 입력 단계를 건너뛴다
 * @param expiresInMinutes   코드 유효 시간
 * @param resendAfterSeconds 다시 보내기까지 기다릴 시간
 */
public record EmailVerificationSendResponse(boolean required, int expiresInMinutes, int resendAfterSeconds) {}
