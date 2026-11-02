package com.zipick.infrastructure.config;

import com.zipick.infrastructure.config.InfraProperties.Category;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InfraPolicyTest {

    private static final Category SUBWAY = new Category("SUBWAY", "SW8", "지하철역", 1500);
    private static final Category MART = new Category("MART", "MT1", "대형마트", 1000);

    @Test
    @DisplayName("정책 서명은 설정 순서와 무관하게 같은 값이 나온다")
    void signatureIsOrderIndependent() {
        String first = InfraPolicy.createSignature(List.of(SUBWAY, MART));
        String second = InfraPolicy.createSignature(List.of(MART, SUBWAY));

        assertThat(first).isEqualTo(second);
        assertThat(first).isEqualTo("MART:MT1:1000|SUBWAY:SW8:1500");
    }

    @Test
    @DisplayName("카테고리별 반경이 바뀌면 정책 서명이 달라져 재수집 대상이 된다")
    void signatureChangesWhenRadiusChanges() {
        String before = InfraPolicy.createSignature(List.of(SUBWAY, MART));
        String after = InfraPolicy.createSignature(
                List.of(new Category("SUBWAY", "SW8", "지하철역", 2000), MART)
        );

        assertThat(after).isNotEqualTo(before);
    }

    @Test
    @DisplayName("카테고리가 추가되면 정책 서명이 달라진다")
    void signatureChangesWhenCategoryAdded() {
        String before = InfraPolicy.createSignature(List.of(SUBWAY));
        String after = InfraPolicy.createSignature(List.of(SUBWAY, MART));

        assertThat(after).isNotEqualTo(before);
    }

    @Test
    @DisplayName("설정 순서를 그대로 화면 표시 순서로 쓴다")
    void keepsConfiguredOrder() {
        InfraPolicy policy = new InfraPolicy(new InfraProperties(null, null, List.of(SUBWAY, MART)));

        assertThat(policy.categories()).containsExactly(SUBWAY, MART);
    }

    @Test
    @DisplayName("카테고리 설정이 비어 있으면 기동 시점에 바로 실패한다")
    void rejectsEmptyCategories() {
        assertThatThrownBy(() -> new InfraPolicy(new InfraProperties(null, null, List.of())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("zipick.infra.categories");
    }

    @Test
    @DisplayName("중복된 카카오 카테고리 코드는 거부한다")
    void rejectsDuplicatedKakaoCode() {
        List<Category> duplicated = List.of(SUBWAY, new Category("STATION", "SW8", "역", 1000));

        assertThatThrownBy(() -> new InfraPolicy(new InfraProperties(null, null, duplicated)))
                .isInstanceOf(IllegalStateException.class);
    }
}
