package com.zipick.evaluation;

import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

final class GeminiEvaluationSettings {

    private GeminiEvaluationSettings() {
    }

    static Settings load() throws IOException {
        String environmentKey = System.getenv("GEMINI_API_KEY");
        String environmentModel = System.getenv("GEMINI_MODEL");
        if (environmentKey != null && !environmentKey.isBlank()) {
            return new Settings(environmentKey,
                    environmentModel == null || environmentModel.isBlank()
                            ? "gemini-3.5-flash-lite" : environmentModel);
        }

        Path[] candidates = {
                Path.of("zipick-backend", "config", "application-secret.yml"),
                Path.of("config", "application-secret.yml")
        };
        for (Path candidate : candidates) {
            if (!Files.exists(candidate)) continue;
            List<PropertySource<?>> sources = new YamlPropertySourceLoader().load(
                    "gemini-evaluation", new FileSystemResource(candidate)
            );
            for (PropertySource<?> source : sources) {
                Object key = source.getProperty("gemini.api.key");
                Object model = source.getProperty("zipick.gemini-pdf-parser.model");
                if (key != null && !key.toString().isBlank()) {
                    return new Settings(key.toString(), model == null || model.toString().isBlank()
                            ? "gemini-3.5-flash-lite" : model.toString());
                }
            }
        }
        throw new IllegalStateException("application-secret.yml의 gemini.api.key를 찾지 못했습니다.");
    }

    record Settings(String key, String model) {
    }
}
