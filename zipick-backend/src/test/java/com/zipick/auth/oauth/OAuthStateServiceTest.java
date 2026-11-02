package com.zipick.auth.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zipick.global.error.BusinessException;
import com.zipick.global.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;

class OAuthStateServiceTest {

    private final OAuthStateService service = new OAuthStateService();

    @Test
    @DisplayName("state는 호출할 때마다 다른 값이 나온다")
    void generateIsUnique() {
        String first = service.generate();
        String second = service.generate();

        assertThat(first).isNotBlank().isNotEqualTo(second);
        // URL에 그대로 실리므로 인코딩이 필요한 문자가 없어야 한다.
        assertThat(first).matches("[A-Za-z0-9_-]+");
    }

    @Test
    @DisplayName("쿠키 값과 요청 값이 같으면 통과한다")
    void verifyMatching() {
        String state = service.generate();

        assertThatCode(() -> service.verify(state, state)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("값이 다르면 OAUTH_STATE_INVALID")
    void verifyMismatch() {
        assertThatThrownBy(() -> service.verify("cookie-state", "attacker-state"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.OAUTH_STATE_INVALID);
    }

    @ParameterizedTest(name = "쿠키=[{0}] 요청=[{1}]")
    @DisplayName("쿠키가 만료됐거나 값이 비어 있으면 OAUTH_STATE_INVALID")
    @CsvSource({"'', some-state", "some-state, ''", "'   ', some-state"})
    void verifyBlank(String cookieState, String requestState) {
        assertThatThrownBy(() -> service.verify(cookieState, requestState))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.OAUTH_STATE_INVALID);
    }

    @ParameterizedTest
    @DisplayName("쿠키가 아예 없으면 OAUTH_STATE_INVALID")
    @NullSource
    void verifyNullCookie(String cookieState) {
        assertThatThrownBy(() -> service.verify(cookieState, "some-state"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.OAUTH_STATE_INVALID);

        assertThatThrownBy(() -> service.verify("some-state", cookieState))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.OAUTH_STATE_INVALID);
    }
}
