package com.zipick.global;

import com.zipick.external.myhome.client.MyHomeApiClient;
import com.zipick.external.myhome.controller.MyHomeApiController;
import com.zipick.notice.controller.NoticeSyncController;
import com.zipick.notice.service.MyHomeSyncService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * permitAll 경로의 두 컨트롤러는 운영 웹 서버에서 설정으로 등록하지 않는다.
 * 설정이 없으면(로컬) 지금처럼 등록된다.
 */
class PermitAllEndpointToggleTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(MyHomeApiClient.class, () -> mock(MyHomeApiClient.class))
            .withBean(MyHomeSyncService.class, () -> mock(MyHomeSyncService.class))
            .withUserConfiguration(MyHomeApiController.class, NoticeSyncController.class);

    @Test
    void registeredByDefault() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(MyHomeApiController.class);
            assertThat(context).hasSingleBean(NoticeSyncController.class);
        });
    }

    @Test
    void notRegisteredWhenDisabledForProduction() {
        runner.withPropertyValues("zipick.external-proxy.enabled=false", "zipick.collector.api-enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(MyHomeApiController.class);
                    assertThat(context).doesNotHaveBean(NoticeSyncController.class);
                });
    }

    @Test
    void togglesAreIndependent() {
        runner.withPropertyValues("zipick.external-proxy.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(MyHomeApiController.class);
                    assertThat(context).hasSingleBean(NoticeSyncController.class);
                });
    }
}
