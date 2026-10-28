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

    /**
     * 실제로 Docker 에 붙어 본다.
     *
     * <p>⚠️ {@code isDockerAvailable()} 로는 판정할 수 없다. 데몬이 꺼져 있어도 true 를 돌려주는 것을
     * 확인했다(설정된 docker host 가 있으면 '사용 가능'으로 보는 듯하다). 그 값을 믿었더니
     * 통합 테스트가 건너뛰어지지 않고 컨테이너를 띄우다 실패했다.
     *
     * <p>{@code client()} 는 연결·핑까지 시도하므로 데몬이 없거나 응답하지 않으면 예외를 던진다.
     * 호출 비용이 있지만 클래스 로딩 때 한 번만 돈다.
     */
    private static boolean probe() {
        try {
            DockerClientFactory.instance().client();
            return true;
        } catch (Throwable ignored) {
            // 데몬이 꺼져 있거나 소켓이 없으면 예외가 난다. 그래도 빌드는 계속되어야 한다.
            return false;
        }
    }
}
