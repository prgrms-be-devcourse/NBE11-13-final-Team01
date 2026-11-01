package com.zipick.auth.oauth;

import static org.assertj.core.api.Assertions.assertThat;

import com.zipick.auth.oauth.dto.KakaoUserResponse;
import com.zipick.auth.oauth.dto.KakaoUserResponse.KakaoAccount;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

// 어떤 이메일을 "믿고 저장할지" 정하는 규칙이라 분기를 모두 확인한다.
class KakaoUserResponseTest {

    @Test
    @DisplayName("검증을 모두 통과한 이메일만 돌려준다")
    void verifiedEmail() {
        KakaoUserResponse response = new KakaoUserResponse(
                12345L, new KakaoAccount("  user@example.com  ", true, true));

        assertThat(response.verifiedEmail()).isEqualTo("user@example.com");
        assertThat(response.providerId()).isEqualTo("12345");
    }

    @ParameterizedTest(name = "email={0}, valid={1}, verified={2}")
    @DisplayName("미인증·무효·미제공 이메일은 신뢰하지 않고 null을 돌려준다")
    @CsvSource(nullValues = "null", value = {
            "user@example.com, true,  false",   // 소유 인증 안 함
            "user@example.com, false, true",    // 카카오계정에서 무효 상태
            "user@example.com, null,  true",    // 동의하지 않아 플래그가 없음
            "user@example.com, true,  null",
            "null,             true,  true",    // 이메일 동의 거부
            "'',               true,  true",
    })
    void untrustedEmail(String email, Boolean valid, Boolean verified) {
        KakaoUserResponse response = new KakaoUserResponse(1L, new KakaoAccount(email, valid, verified));

        assertThat(response.verifiedEmail()).isNull();
    }

    @Test
    @DisplayName("kakao_account 자체가 없어도 터지지 않는다")
    void noAccount() {
        KakaoUserResponse response = new KakaoUserResponse(1L, null);

        assertThat(response.verifiedEmail()).isNull();
        assertThat(response.providerId()).isEqualTo("1");
    }

    @Test
    @DisplayName("회원번호가 없으면 providerId도 null이다")
    void noId() {
        assertThat(new KakaoUserResponse(null, null).providerId()).isNull();
    }
}
