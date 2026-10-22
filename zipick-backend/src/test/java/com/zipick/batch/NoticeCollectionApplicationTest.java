package com.zipick.batch;

import com.zipick.notice.dto.NoticeSyncResponse;
import com.zipick.notice.service.MyHomeSyncService;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

import java.lang.reflect.Constructor;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NoticeCollectionApplicationTest {

    @Test
    void runsOneSynchronizationAndClosesContext() {
        ConfigurableApplicationContext context = mock(ConfigurableApplicationContext.class);
        MyHomeSyncService sync = mock(MyHomeSyncService.class);
        when(context.getBean(MyHomeSyncService.class)).thenReturn(sync);
        when(sync.synchronize()).thenReturn(new NoticeSyncResponse(
                10, 2, 1, 3, 4, 0, 0, 0, Instant.now(), Instant.now()
        ));

        try (MockedConstruction<SpringApplication> construction = mockConstruction(
                SpringApplication.class,
                (application, ignored) -> when(application.run(any(String[].class))).thenReturn(context)
        )) {
            NoticeCollectionApplication.main(new String[]{"--spring.profiles.active=local"});
            assertThat(construction.constructed()).hasSize(1);
        }

        verify(sync).synchronize();
        verify(context).close();
    }

    @Test
    void hasNoPublicConstructor() throws Exception {
        Constructor<NoticeCollectionApplication> constructor = NoticeCollectionApplication.class
                .getDeclaredConstructor();
        assertThat(constructor.canAccess(null)).isFalse();
    }
}
