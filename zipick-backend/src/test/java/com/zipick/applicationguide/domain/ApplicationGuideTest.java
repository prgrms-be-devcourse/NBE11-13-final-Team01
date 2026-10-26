package com.zipick.applicationguide.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

/**
 * 저장해 둔 가이드를 언제 다시 써도 되는지.
 *
 * inputHash 대신 "이 결과가 무엇에 의존하는가"를 컬럼으로 둔 설계라, 네 값 중 하나라도
 * 달라지면 다시 만들어야 한다. 특히 프로필 수정은 그 사용자 것만 무효가 되어야 한다.
 */
class ApplicationGuideTest {

    private static final LocalDateTime PROFILE_AT = LocalDateTime.of(2026, 10, 20, 9, 0);

    private ApplicationGuide guide() {
        return new ApplicationGuide(1L, 10L, "{}", "{}", "prompt-v1", "reducer-v1", "flash-lite",
                PROFILE_AT, LocalDateTime.of(2026, 10, 25, 9, 0));
    }

    @Test
    void 네_값이_모두_같으면_저장된_결과를_다시_쓴다() {
        assertThat(guide().isReusable("prompt-v1", "reducer-v1", "flash-lite", PROFILE_AT)).isTrue();
    }

    @Test
    void 프롬프트를_고치면_다시_만든다() {
        assertThat(guide().isReusable("prompt-v2", "reducer-v1", "flash-lite", PROFILE_AT)).isFalse();
    }

    @Test
    void 축약_규칙을_고치면_다시_만든다() {
        assertThat(guide().isReusable("prompt-v1", "reducer-v2", "flash-lite", PROFILE_AT)).isFalse();
    }

    @Test
    void 모델을_바꾸면_다시_만든다() {
        assertThat(guide().isReusable("prompt-v1", "reducer-v1", "pro", PROFILE_AT)).isFalse();
    }

    @Test
    void 사용자가_내_정보를_고치면_다시_만든다() {
        assertThat(guide().isReusable("prompt-v1", "reducer-v1", "flash-lite", PROFILE_AT.plusMinutes(1))).isFalse();
    }

    @Test
    void 다시_만들면_결과와_기준값이_함께_갱신된다() {
        ApplicationGuide guide = guide();
        LocalDateTime now = LocalDateTime.of(2026, 10, 26, 10, 0);

        guide.refresh("{\"summary\":\"x\"}", "{\"S1\":{}}", "prompt-v2", "reducer-v2", "pro", PROFILE_AT, now);

        assertThat(guide.getResultJson()).contains("summary");
        assertThat(guide.getGeneratedAt()).isEqualTo(now);
        assertThat(guide.isReusable("prompt-v2", "reducer-v2", "pro", PROFILE_AT)).isTrue();
    }
}
