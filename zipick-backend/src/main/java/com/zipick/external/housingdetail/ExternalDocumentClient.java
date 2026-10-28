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

@Component
public class ExternalDocumentClient {

    private static final Pattern CHARSET_PATTERN = Pattern.compile("charset=([^;\\s]+)", Pattern.CASE_INSENSITIVE);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public String getText(URI uri) {
        HttpResponse<byte[]> response = get(uri);
        Charset charset = response.headers().firstValue("Content-Type")
                .map(this::charsetFrom)
                .orElse(StandardCharsets.UTF_8);
        return new String(response.body(), charset);
    }

    public byte[] getBytes(URI uri) {
        return get(uri).body();
    }

    private HttpResponse<byte[]> get(URI uri) {
        return ExternalApiMetrics.measure("institution", institution(uri), () -> request(uri));
    }

    private HttpResponse<byte[]> request(URI uri) {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(REQUEST_TIMEOUT)
                .header("User-Agent", "ZIPICK housing collector/1.0")
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
