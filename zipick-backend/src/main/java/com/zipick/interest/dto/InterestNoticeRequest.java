package com.zipick.interest.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record InterestNoticeRequest(
        @NotNull Long noticeId,
        @Size(max = 200) String memo
) {}
