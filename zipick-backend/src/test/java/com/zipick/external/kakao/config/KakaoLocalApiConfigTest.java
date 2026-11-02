package com.zipick.external.kakao.config;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 타임아웃이 실제로 걸리는지 확인한다.
 *
 * RestClient.builder() 를 직접 쓰면 spring.http.clients.* 전역 설정이 적용되지 않아,
 * 설정을 넣어 두고도 무한 대기하는 상황이 생길 수 있다. 응답하지 않는 서버로 그 점을 검증한다.
 */
class KakaoLocalApiConfigTest {

    /** 읽기 타임아웃(500ms)보다 훨씬 길게 붙잡아 둬야 '타임아웃이 걸렸다'를 구분할 수 있다. */
    private static final long SERVER_HANG_MILLIS = 3_000;

    private HttpServer server;
    private ExecutorService executor;

    @BeforeEach
    void startHangingServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "hanging-test-server");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(executor);
        server.createContext("/", exchange -> {
            try {
                Thread.sleep(SERVER_HANG_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
        executor.shutdownNow();
    }

    private String baseUrl() {
        return "http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort();
    }

    @Test
    @DisplayName("응답이 없으면 읽기 타임아웃으로 끊는다 (무한 대기하지 않는다)")
    void appliesReadTimeout() {
        RestClient client = KakaoLocalApiConfig.restClient(
                baseUrl(), Duration.ofSeconds(1), Duration.ofMillis(500)
        );

        long startedAt = System.nanoTime();
        assertThatThrownBy(() -> client.get().uri("/").retrieve().body(String.class))
                .isInstanceOf(RuntimeException.class);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

        // 타임아웃이 걸리지 않았다면 서버가 붙잡는 3초를 그대로 기다렸을 것이다.
        assertThat(elapsed).isLessThan(Duration.ofMillis(2_000));
    }

    @Test
    @DisplayName("대조: RestClient.builder() 만 쓰면 타임아웃이 걸리지 않아 서버가 놓아줄 때까지 기다린다")
    void plainBuilderHasNoTimeout() {
        // 이 테스트가 깨지면 Boot 가 RestClient.builder() 에도 기본 타임아웃을 주기 시작했다는 뜻이다.
        // 그때는 위 restClient(...) 의 요청 팩토리 설정이 더 이상 필요 없는지 다시 판단하면 된다.
        RestClient plain = RestClient.builder().baseUrl(baseUrl()).build();

        long startedAt = System.nanoTime();
        assertThatThrownBy(() -> plain.get().uri("/").retrieve().body(String.class))
                .isInstanceOf(RuntimeException.class);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

        assertThat(elapsed).isGreaterThan(Duration.ofMillis(2_500));
    }

    @Test
    @DisplayName("운영 기본값은 connect 3초, read 10초다")
    void usesConservativeDefaults() {
        // read 를 짧게 잡으면 좌표 변환(첫 실패에 회차 중단)이 일시적 지연을 실패로 오해한다.
        assertThat(KakaoLocalApiConfig.CONNECT_TIMEOUT).isEqualTo(Duration.ofSeconds(3));
        assertThat(KakaoLocalApiConfig.READ_TIMEOUT).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("빈 생성 경로도 같은 방식으로 타임아웃을 건다")
    void beanUsesConfiguredTimeouts() {
        RestClient client = new KakaoLocalApiConfig()
                .kakaoLocalRestClient(new KakaoLocalApiProperties("test-key", null));

        assertThat(client).isNotNull();
    }
}
