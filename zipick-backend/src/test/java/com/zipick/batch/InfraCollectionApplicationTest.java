package com.zipick.batch;

import com.zipick.infrastructure.service.HousingInfraCollectionService;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;

import java.lang.reflect.Constructor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InfraCollectionApplicationTest {

    @Test
    void runsInfraCollectionOnceWithoutWebServerAndClosesContext() {
        ConfigurableApplicationContext context = mock(ConfigurableApplicationContext.class);
        HousingInfraCollectionService infra = mock(HousingInfraCollectionService.class);
        when(context.getBean(HousingInfraCollectionService.class)).thenReturn(infra);

        try (MockedConstruction<SpringApplication> construction = mockConstruction(
                SpringApplication.class,
                (application, ignored) -> when(application.run(any(String[].class))).thenReturn(context)
        )) {
            InfraCollectionApplication.main(new String[]{"--spring.profiles.active=local"});
            SpringApplication application = construction.constructed().getFirst();
            verify(application).setWebApplicationType(WebApplicationType.NONE);
        }

        verify(infra).collect("일일 수집");
        verify(context).close();
    }

    @Test
    void hasNoPublicConstructor() throws Exception {
        Constructor<InfraCollectionApplication> constructor = InfraCollectionApplication.class.getDeclaredConstructor();
        assertThat(constructor.canAccess(null)).isFalse();
    }
}
