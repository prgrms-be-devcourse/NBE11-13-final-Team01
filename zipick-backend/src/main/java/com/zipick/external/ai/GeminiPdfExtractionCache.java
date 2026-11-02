package com.zipick.external.ai;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

/** 같은 PDF와 같은 프롬프트 버전은 다시 호출하지 않고 원본 AI 응답을 재사용한다. */
final class GeminiPdfExtractionCache {

    private final Path directory;

    GeminiPdfExtractionCache(Path cacheDirectory) {
        this.directory = cacheDirectory.resolve("responses");
    }

    Optional<String> read(String key) {
        Path file = file(key);
        if (!Files.exists(file)) return Optional.empty();
        try {
            return Optional.of(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new IllegalStateException("Gemini PDF 캐시를 읽지 못했습니다: " + file, exception);
        }
    }

    void write(String key, String response) {
        writeAtomically(file(key), response);
    }

    Optional<String> readProgress(String key) {
        Path file = progressFile(key);
        if (!Files.exists(file)) return Optional.empty();
        try {
            return Optional.of(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new IllegalStateException("Gemini PDF 부분 진행 상태를 읽지 못했습니다: " + file, exception);
        }
    }

    void writeProgress(String key, String response) {
        writeAtomically(progressFile(key), response);
    }

    void deleteProgress(String key) {
        try {
            Files.deleteIfExists(progressFile(key));
        } catch (IOException exception) {
            throw new IllegalStateException("Gemini PDF 부분 진행 상태를 삭제하지 못했습니다.", exception);
        }
    }

    private void writeAtomically(Path file, String response) {
        try {
            Files.createDirectories(directory);
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temporary, response, StandardCharsets.UTF_8);
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException exception) {
            throw new IllegalStateException("Gemini PDF 캐시를 저장하지 못했습니다: " + file, exception);
        }
    }

    private Path file(String key) {
        return directory.resolve(key + ".json");
    }

    private Path progressFile(String key) {
        return directory.resolve(key + ".partial.json");
    }
}
