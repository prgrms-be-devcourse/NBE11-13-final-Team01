package com.zipick.external.ai;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/** 수동 재검증 시 지정한 외부 공고만 처리합니다. 설정하지 않으면 기존 동작을 유지합니다. */
public final class AiReprocessingScope {
    private AiReprocessingScope() { }
    private static Set<String> selected() {
        String value = System.getProperty("zipick.ai.reprocess.external-ids", "");
        return Arrays.stream(value.split(",")).map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toUnmodifiableSet());
    }
    public static boolean enabled() { return !selected().isEmpty(); }
    public static boolean includes(String externalId) { return selected().contains(externalId); }
}
