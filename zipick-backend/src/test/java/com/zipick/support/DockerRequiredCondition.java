package com.zipick.support;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Docker 가 없으면 테스트를 건너뛴다. {@link PostgresRepositoryTestBase} 에 달아 쓴다.
 *
 * <p>⚠️ JUnit 의 {@code @EnabledIf} 를 상위 클래스에 달면 하위 클래스에 적용되지 않는다.
 * {@code @EnabledIf} 자체는 {@code @Inherited} 가 아니라서, 상속으로 등록된
 * {@code EnabledIfCondition} 이 정작 애너테이션을 못 찾고 "조건 없음 = 활성"으로 판단한다.
 * 그래서 Docker 가 없는데도 컨테이너를 띄우려다 전부 실패했다.
 *
 * <p>반면 {@code @ExtendWith} 로 등록한 Extension 은 하위 클래스에 상속된다.
 * 조건을 Extension 으로 직접 구현하면 애너테이션 탐색이 필요 없어 그 함정을 피한다.
 * 앞으로 {@link PostgresRepositoryTestBase} 를 상속하는 테스트는 따로 표시하지 않아도 된다.
 */
public class DockerRequiredCondition implements ExecutionCondition {

    private static final ConditionEvaluationResult ENABLED =
            ConditionEvaluationResult.enabled("Docker 를 쓸 수 있어 PostgreSQL 컨테이너를 띄운다");

    private static final ConditionEvaluationResult DISABLED =
            ConditionEvaluationResult.disabled("Docker 를 쓸 수 없어 PostgreSQL 통합 테스트를 건너뛴다");

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        return DockerAvailability.isAvailable() ? ENABLED : DISABLED;
    }
}
