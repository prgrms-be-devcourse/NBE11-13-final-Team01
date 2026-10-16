package com.zipick.notice.service;

import org.springframework.http.MediaType;

public record MapHousingAsset(
        byte[] content,
        MediaType contentType,
        String fileName
) {
}
