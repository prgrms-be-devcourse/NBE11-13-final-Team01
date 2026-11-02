package com.zipick.auth.dto.request;

import com.zipick.user.entity.User;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record SignupRequest(

        @NotBlank(message = "이메일은 필수입니다.")
        @Email(message = "이메일 형식이 올바르지 않습니다.")
        @Size(max = 255, message = "이메일은 255자 이하여야 합니다.")
        String email,

        @NotBlank(message = "비밀번호는 필수입니다.")
        @Size(min = 8, max = 64, message = "비밀번호는 8자 이상 64자 이하여야 합니다.")
        // 영문과 숫자를 모두 포함하고 ASCII 문자만 허용한다.
        // BCrypt는 72바이트를 넘는 입력을 처리하지 못하므로 멀티바이트 문자를 받지 않는다.
        @Pattern(
                regexp = "^(?=.*[A-Za-z])(?=.*[0-9])[A-Za-z0-9!@#$%^&*()_+=-]+$",
                message = "비밀번호는 영문과 숫자를 모두 포함해야 합니다."
        )
        String password,

        // 이메일 인증을 마치고 받은 토큰. 서버가 인증을 요구할 때(EMAIL_VERIFICATION_REQUIRED) 필수
        @Size(max = 128)
        String verificationToken
) {
    public User toUser(String encodedPassword) {
        return User.of(email, encodedPassword);
    }
}
