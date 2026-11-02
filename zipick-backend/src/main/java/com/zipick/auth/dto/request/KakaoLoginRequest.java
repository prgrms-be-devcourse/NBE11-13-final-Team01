package com.zipick.auth.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 카카오 콜백에서 받은 값. 둘 다 1회용이다. */
public record KakaoLoginRequest(

        @NotBlank(message = "인가 코드는 필수입니다.")
        @Size(max = 512)
        String code,

        @NotBlank(message = "state는 필수입니다.")
        @Size(max = 128)
        String state
) {
}
