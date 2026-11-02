package com.zipick.notification.campaign;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;

/** @param scheduledAt 비우면 "지금"(단, 21~08시면 다음 08:00 로 미뤄진다) */
public record CampaignRequest(
        @NotBlank @Size(max = 100) String title,
        @NotBlank @Size(max = 200) String headline,
        @NotBlank @Size(max = 5000) String message,
        LocalDateTime scheduledAt
) {}
