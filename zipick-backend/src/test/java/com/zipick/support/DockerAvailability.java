package com.zipick.support;

import org.testcontainers.DockerClientFactory;

/**
 * Docker 를 쓸 수 있는지 한 번만 확인해 둔다.
 *
 * <p>Docker 가 없는 환경(일부 로컬·CI)에서도 빌드가 깨지지 않도록 PostgreSQL 통합 테스트를 건너뛰는 데 쓴다.
 * 건너뛰면 실제 DB 검증이 이뤄지지 않은 것이므로, 테스트 결과를 볼 때 skipped 개수를 함께 봐야 한다.
 */
public final class DockerAvailability {

    private static final boolean AVAILABLE = probe();

    private DockerAvailability() {
    }

    public static boolean isAvailable() {
        return AVAILABLE;
    }

    private static boolean probe() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable ignored) {
            // 데몬이 꺼져 있거나 소켓이 없으면 예외가 난다. 그래도 빌드는 계속되어야 한다.
            return false;
        }
    }
}
