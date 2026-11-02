package com.zipick.auth.jwt;

// Refresh Token을 Access Token 자리에 그대로 쓰는 것을 막기 위해 토큰에 타입을 담는다.
public enum TokenType {
    ACCESS,
    REFRESH
}
