package com.zipick.batch;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Local direct runs publish the same low-cardinality metrics as the operating wrapper. */
final class LocalCollectorObservation implements AutoCloseable {
    private static final Pattern FIELD = Pattern.compile("([A-Za-z]+)=([^\\s]+)");
    private final String type;
    private final Path directory;
    private final long started = System.currentTimeMillis() / 1000;
    private final Map<String, Long> counts = new HashMap<>();
    private final Set<String> notices = new HashSet<>();
    private final Logger root;
    private final AppenderBase<ILoggingEvent> appender;
    private boolean success;

    LocalCollectorObservation(String type, Path directory) {
        this.type = type;
        this.directory = directory;
        root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        appender = new AppenderBase<>() {
            @Override protected void append(ILoggingEvent event) { record(event.getFormattedMessage()); }
        };
        appender.setContext(root.getLoggerContext());
        appender.start();
        root.addAppender(appender);
        writeRunning(1);
    }

    static LocalCollectorObservation start(String type) {
        if (!System.getProperty("os.name", "").toLowerCase().contains("windows")) return null;
        Path cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        Path project = cwd.getFileName().toString().equals("zipick-backend") ? cwd.getParent() : cwd;
        return new LocalCollectorObservation(type, project.resolve("deploy/monitoring/collector-metrics"));
    }

    void attachAfterStartup() {
        // Spring Boot can reset Logback while its application context starts.
        root.detachAppender(appender);
        if (!appender.isStarted()) appender.start();
        root.addAppender(appender);
    }

    synchronized void record(String message) {
        if (message.contains("주택 주소 좌표 변환 완료:")) {
            Matcher m = Pattern.compile("카카오 호출=(\\d+)").matcher(message);
            if (m.find()) counts.merge("kakao_calls", Long.parseLong(m.group(1)), Long::sum);
        }
        if (message.contains("카카오 주소 검색 실패로 좌표 변환을 중단합니다") || message.contains("카카오 키워드 주소 보완 실패로 좌표 변환을 중단합니다")) increment("kakao_errors");
        if (!message.contains("COLLECTOR_METRIC event=")) return;
        Map<String, String> fields = new HashMap<>();
        Matcher matcher = FIELD.matcher(message);
        while (matcher.find()) fields.put(matcher.group(1), matcher.group(2));
        String event = fields.getOrDefault("event", "");
        switch (event) {
            case "notice_error", "detail_error" -> {
                if (fields.containsKey("noticeId")) notices.add(fields.get("noticeId"));
                if (event.equals("detail_error")) increment(event);
            }
            case "source_error", "normalization_error" -> increment(event);
            case "normalization_deferred" -> {
                try { counts.merge(event, Long.parseLong(fields.getOrDefault("count", "0")), Long::sum); }
                catch (NumberFormatException ignored) { }
            }
            case "ai_document_error" -> increment(event + ":" + fields.getOrDefault("reason", "other"));
            case "gemini_http_success" -> increment(event + ":" + fields.getOrDefault("stage", "unknown"));
            case "gemini_http_error", "gemini_final_failure" -> {
                String status = fields.getOrDefault("status", "other");
                String category = status.equals("429") ? "429" : status.matches("5\\d\\d") ? "5xx" : status.equals("network") || status.equals("interrupted") ? "network" : "other";
                increment(event + ":" + fields.getOrDefault("stage", "unknown") + ":" + category);
            }
            default -> { }
        }
    }

    private void increment(String key) { counts.merge(key, 1L, Long::sum); }
    void success() { success = true; }

    private void writeRunning(int value) {
        write("zipick_collector_" + type + "_running.prom", "zipick_collector_running{job_type=\"" + type + "\"} " + value + "\n" +
                "zipick_collector_started_timestamp_seconds{job_type=\"" + type + "\"} " + started + "\n");
    }

    @Override public synchronized void close() {
        root.detachAppender(appender);
        appender.stop();
        long now = System.currentTimeMillis() / 1000;
        Path previous = directory.resolve("zipick_collector_" + type + ".prom");
        long lastSuccess = 0;
        if (success) lastSuccess = now;
        else try {
            Matcher m = Pattern.compile("zipick_collector_last_success_timestamp_seconds\\{[^}]*} (\\d+)").matcher(Files.readString(previous));
            if (m.find()) lastSuccess = Long.parseLong(m.group(1));
        } catch (IOException | NumberFormatException ignored) { }
        StringBuilder b = new StringBuilder();
        metric(b,"last_run_success",success ? 1 : 0, "");
        metric(b,"last_success_timestamp_seconds",lastSuccess, "");
        metric(b,"last_duration_seconds",Math.max(0, now-started), "");
        metric(b,"last_source_errors",get("source_error"), "");
        metric(b,"last_notice_errors",notices.size(), "");
        metric(b,"last_detail_errors",get("detail_error"), "");
        metric(b,"last_normalization_deferred",get("normalization_deferred"), "");
        metric(b,"last_normalization_errors",get("normalization_error"), "");
        if (counts.containsKey("kakao_calls")) metric(b,"last_kakao_geocoding_calls",get("kakao_calls"), "");
        if (counts.containsKey("kakao_calls") || counts.containsKey("kakao_errors")) metric(b,"last_kakao_geocoding_errors",get("kakao_errors"), "");
        for (String reason : new String[]{"incomplete","empty","exception"}) metric(b,"last_ai_document_errors",get("ai_document_error:"+reason), ",reason=\""+reason+"\"");
        for (String stage : new String[]{"extraction","normalization"}) {
            String stageLabel = ",stage=\""+stage+"\"";
            metric(b,"last_gemini_http_success",get("gemini_http_success:"+stage),stageLabel);
            for (String category : new String[]{"429","5xx","network","other"}) {
                String labels = stageLabel+",category=\""+category+"\"";
                metric(b,"last_gemini_http_errors",get("gemini_http_error:"+stage+":"+category),labels);
                metric(b,"last_gemini_final_failures",get("gemini_final_failure:"+stage+":"+category),labels);
            }
        }
        write("zipick_collector_"+type+".prom",b.toString());
        writeRunning(0);
    }
    private long get(String key) { return counts.getOrDefault(key,0L); }
    private void metric(StringBuilder b,String name,long value,String labels) {
        b.append("zipick_collector_").append(name).append("{job_type=\"").append(type).append('"').append(labels).append("} ").append(value).append('\n');
    }
    private void write(String filename,String content) {
        try {
            Files.createDirectories(directory);
            Path temp=Files.createTempFile(directory,".collector-", ".tmp");
            Files.writeString(temp,content,StandardCharsets.UTF_8);
            try { Files.move(temp,directory.resolve(filename),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE); }
            catch (java.nio.file.AtomicMoveNotSupportedException e) { Files.move(temp,directory.resolve(filename),StandardCopyOption.REPLACE_EXISTING); }
        } catch (IOException e) { System.err.println("수집기 모니터링 기록 실패: " + e.getMessage()); }
    }
}
