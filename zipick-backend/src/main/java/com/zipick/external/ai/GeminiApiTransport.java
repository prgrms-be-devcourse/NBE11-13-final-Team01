package com.zipick.external.ai;

@FunctionalInterface
interface GeminiApiTransport {
    String generate(String requestBody);
}
