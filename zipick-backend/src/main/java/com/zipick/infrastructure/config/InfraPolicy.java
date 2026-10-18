package com.zipick.infrastructure.config;

import com.zipick.infrastructure.config.InfraProperties.Category;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 현재 적용 중인 수집 정책. 기동 시 한 번 계산해 재사용한다.
 *
 * policySignature 는 "어떤 카테고리를 어떤 반경으로 수집했는가"를 한 줄로 표현한 값이다.
 * 스냅샷에 함께 저장해 두고 현재 값과 비교하면, 카테고리 추가·삭제나
 * 카테고리별 반경 변경을 모두 재수집 대상으로 잡을 수 있다.
 * 해시 대신 평문을 쓰는 이유는 DB 에서 바로 어떤 정책이었는지 읽기 위해서다.
 */
@Component
public class InfraPolicy {

    private final List<Category> categories;
    private final String signature;

    public InfraPolicy(InfraProperties properties) {
        List<Category> configured = properties.categories();
        if (configured.isEmpty()) {
            throw new IllegalStateException("zipick.infra.categories 설정이 비어 있습니다.");
        }
        validateUnique(configured);
        // 화면 표시 순서는 설정 순서를 그대로 따른다.
        this.categories = List.copyOf(configured);
        this.signature = createSignature(configured);
    }

    public List<Category> categories() {
        return categories;
    }

    public String signature() {
        return signature;
    }

    /** 설정 순서와 무관하게 같은 정책이면 같은 값이 나오도록 코드순으로 정렬해 만든다. */
    static String createSignature(List<Category> categories) {
        return categories.stream()
                .sorted(Comparator.comparing(Category::code))
                .map(category -> category.code() + ":" + category.kakaoCode() + ":" + category.radiusM())
                .reduce((left, right) -> left + "|" + right)
                .orElse("");
    }

    private static void validateUnique(List<Category> categories) {
        Set<String> codes = new LinkedHashSet<>();
        Set<String> kakaoCodes = new LinkedHashSet<>();
        for (Category category : categories) {
            if (!codes.add(category.code())) {
                throw new IllegalStateException("인프라 카테고리 코드가 중복됐습니다: " + category.code());
            }
            if (!kakaoCodes.add(category.kakaoCode())) {
                throw new IllegalStateException("카카오 카테고리 코드가 중복됐습니다: " + category.kakaoCode());
            }
        }
    }
}
