package com.zipick.auth.dto.response;

/**
 * 코드 확인 성공. 가입 요청(SignupRequest.verificationToken)에 이 토큰을 넣어야 가입된다.
 * @param expiresInMinutes 토큰 유효 시간
 */
public record EmailVerificationConfirmResponse(String verificationToken, int expiresInMinutes) {}
