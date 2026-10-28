package com.zipick.support;

import com.zipick.global.config.QuerydslConfig;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 운영과 같은 PostgreSQL 에서 도는 Repository 테스트의 바탕.
 *
 * <p>지도·추천 조회는 QueryDSL 이 만드는 JOIN 과 EXISTS 가 실제로 어떻게 도는지에 기대고 있다.
 * Mock 테스트는 "조건이 이렇게 조립된다"까지만 보여 주므로,
 * 서로 다른 호실의 값이 섞이는지 같은 것은 진짜 DB 에서만 드러난다.
 *
 * <p>Docker 가 없으면 이 클래스를 상속한 테스트는 모두 건너뛴다({@link DockerRequiredCondition}).
 * 건너뛰면 실제 DB 검증이 이뤄지지 않은 것이므로 결과의 skipped 개수를 함께 봐야 한다.
 *
 * <p>컨테이너는 전체 테스트에서 하나만 띄워 돌려 쓴다(처음 쓰는 클래스가 시작, JVM 종료 시 정리).
 * 접속 정보는 컨테이너가 임의로 만든 값이라 로컬 개발 DB·비밀 설정 파일을 전혀 읽지 않는다.
 * 스키마는 엔티티에서 만들고(create-drop) 테스트마다 트랜잭션이 롤백되어 서로 간섭하지 않는다.
 */
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.open-in-view=false",
        "spring.jpa.properties.hibernate.format_sql=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(QuerydslConfig.class)
// Docker 가 없으면 건너뛴다. @EnabledIf 는 상위 클래스에서 상속되지 않아 Extension 으로 등록한다
// (이유는 DockerRequiredCondition 주석 참고).
@ExtendWith(DockerRequiredCondition.class)
public abstract class PostgresRepositoryTestBase {

    private static PostgreSQLContainer<?> container;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        PostgreSQLContainer<?> postgres = container();
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    /** 테스트 클래스마다 컨테이너를 다시 띄우면 느리다. 처음 쓰는 쪽에서 한 번만 시작한다. */
    private static synchronized PostgreSQLContainer<?> container() {
        if (container == null) {
            container = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));
            container.start();
        }
        return container;
    }
}
