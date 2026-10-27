package com.zipick.external.ai;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;

/** 무료 RPD를 초과하지 않도록 신규 호출과 후속 호출의 몫을 나눠 관리한다. */
final class GeminiDailyCallBudget {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final ZoneId QUOTA_ZONE = ZoneId.of("America/Los_Angeles");

    private final Path ledger;
    private final int dailyLimit;
    private final int retryReserve;

    GeminiDailyCallBudget(Path cacheDirectory, int dailyLimit, int retryReserve) {
        this.ledger = cacheDirectory.resolve("daily-call-ledger.json");
        this.dailyLimit = dailyLimit;
        this.retryReserve = retryReserve;
    }

    synchronized void acquire(boolean continuation) {
        Ledger current = read();
        int allowed = continuation ? dailyLimit : dailyLimit - retryReserve;
        if (current.used() >= allowed) {
            String purpose = continuation ? "전체" : "신규 분석";
            throw new GeminiDailyQuotaExceededException(
                    "Gemini " + purpose + " 일일 호출 예산을 모두 사용했습니다: "
                            + current.used() + "/" + allowed
            );
        }
        write(new Ledger(current.date(), current.used() + 1));
    }

    synchronized int usedToday() {
        return read().used();
    }

    private Ledger read() {
        LocalDate today = LocalDate.now(QUOTA_ZONE);
        if (!Files.exists(ledger)) return new Ledger(today, 0);
        try {
            JsonNode root = JSON.readTree(Files.readString(ledger, StandardCharsets.UTF_8));
            LocalDate storedDate = LocalDate.parse(root.path("date").asText(today.toString()));
            return storedDate.equals(today)
                    ? new Ledger(today, root.path("used").asInt(0))
                    : new Ledger(today, 0);
        } catch (Exception exception) {
            throw new IllegalStateException("Gemini 호출량 기록을 읽지 못했습니다: " + ledger, exception);
        }
    }

    private void write(Ledger value) {
        try {
            Files.createDirectories(ledger.getParent());
            Path temporary = ledger.resolveSibling(ledger.getFileName() + ".tmp");
            Files.writeString(temporary, JSON.writeValueAsString(Map.of(
                    "date", value.date().toString(), "used", value.used()
            )), StandardCharsets.UTF_8);
            Files.move(temporary, ledger, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException exception) {
            throw new IllegalStateException("Gemini 호출량 기록을 저장하지 못했습니다: " + ledger, exception);
        }
    }

    private record Ledger(LocalDate date, int used) {
    }
}
