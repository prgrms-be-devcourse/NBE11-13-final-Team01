package com.zipick.external.housingdetail;

import com.zipick.external.ExternalApiMetrics;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.security.KeyStore;
import java.security.cert.CertStore;
import java.security.cert.CertificateFactory;
import java.security.cert.CollectionCertStoreParameters;
import java.security.cert.PKIXBuilderParameters;
import java.security.cert.TrustAnchor;
import java.security.cert.X509CertSelector;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import javax.net.ssl.CertPathTrustManagerParameters;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

@Component
public class ExternalDocumentClient {

    private static final Pattern CHARSET_PATTERN = Pattern.compile("charset=([^;\\s]+)", Pattern.CASE_INSENSITIVE);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .sslContext(documentSslContext())
            .build();

    private static SSLContext documentSslContext() {
        try {
            var defaults = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            defaults.init((KeyStore) null);
            var manager = Arrays.stream(defaults.getTrustManagers())
                    .filter(X509TrustManager.class::isInstance).map(X509TrustManager.class::cast)
                    .findFirst().orElseThrow();
            Set<TrustAnchor> roots = Arrays.stream(manager.getAcceptedIssuers())
                    .map(certificate -> new TrustAnchor(certificate, null)).collect(Collectors.toSet());
            var parameters = new PKIXBuilderParameters(roots, new X509CertSelector());
            parameters.setRevocationEnabled(false);
            try (var input = ExternalDocumentClient.class.getResourceAsStream(
                    "/certificates/globalsign-gcc-r3-ev-tls-ca-2025.crt")) {
                if (input == null) throw new IllegalStateException("인증기관 중간 인증서가 없습니다");
                var certificate = CertificateFactory.getInstance("X.509").generateCertificate(input);
                parameters.addCertStore(CertStore.getInstance("Collection",
                        new CollectionCertStoreParameters(java.util.List.of(certificate))));
            }
            var factory = TrustManagerFactory.getInstance("PKIX");
            factory.init(new CertPathTrustManagerParameters(parameters));
            var context = SSLContext.getInstance("TLS");
            context.init(null, factory.getTrustManagers(), null);
            return context;
        } catch (Exception exception) {
            throw new IllegalStateException("외부 문서 TLS 검증 설정에 실패했습니다", exception);
        }
    }

    public String getText(URI uri) {
        long started = System.nanoTime();
        try {
            HttpResponse<byte[]> response = get(uri);
            Charset charset = response.headers().firstValue("Content-Type")
                    .map(this::charsetFrom)
                    .orElse(StandardCharsets.UTF_8);
            return new String(response.body(), charset);
        } finally {
            com.zipick.external.ai.GeminiPdfStageTiming.externalFetch(false, System.nanoTime() - started);
        }
    }

    public byte[] getBytes(URI uri) {
        long started = System.nanoTime();
        try {
            return get(uri).body();
        } finally {
            com.zipick.external.ai.GeminiPdfStageTiming.externalFetch(true, System.nanoTime() - started);
        }
    }

    private HttpResponse<byte[]> get(URI uri) {
        return ExternalApiMetrics.measure("institution", institution(uri), () -> request(uri));
    }

    private HttpResponse<byte[]> request(URI uri) {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(REQUEST_TIMEOUT)
                .header("User-Agent", userAgent(uri))
                .GET()
                .build();
        try {
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("외부 문서 요청 실패: " + response.statusCode() + " " + safeUri(uri));
            }
            return response;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("외부 문서 요청이 중단되었습니다: " + safeUri(uri), exception);
        } catch (IOException exception) {
            throw new IllegalStateException("외부 문서를 가져오지 못했습니다: " + safeUri(uri), exception);
        }
    }

    private String userAgent(URI uri) {
        if ("www.kohom.or.kr".equalsIgnoreCase(uri.getHost())) {
            return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
        }
        return "ZIPICK housing collector/1.0";
    }

    private String institution(URI uri) {
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (host.equals("i-sh.co.kr") || host.endsWith(".i-sh.co.kr")) return "sh";
        if (host.equals("gh.or.kr") || host.endsWith(".gh.or.kr")) return "gh";
        if (host.equals("lh.or.kr") || host.endsWith(".lh.or.kr")) return "lh";
        if (host.equals("khug.or.kr") || host.endsWith(".khug.or.kr")) return "hug";
        return "other";
    }

    private Charset charsetFrom(String contentType) {
        Matcher matcher = CHARSET_PATTERN.matcher(contentType);
        if (!matcher.find()) {
            return StandardCharsets.UTF_8;
        }
        try {
            return Charset.forName(matcher.group(1).replace("\"", "").trim());
        } catch (Exception ignored) {
            return StandardCharsets.UTF_8;
        }
    }

    private String safeUri(URI uri) {
        return uri.getScheme() + "://" + uri.getHost()
                + (uri.getPath() == null ? "" : uri.getPath().toLowerCase(Locale.ROOT));
    }
}
