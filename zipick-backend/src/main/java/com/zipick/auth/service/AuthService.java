package com.zipick.auth.service;

import com.zipick.auth.dto.request.LoginRequest;
import com.zipick.auth.dto.request.SignupRequest;
import com.zipick.auth.dto.response.SignupResponse;
import com.zipick.auth.userdetails.CustomUserDetails;
import com.zipick.auth.userdetails.SocialAccountLoginException;
import com.zipick.global.error.BusinessException;
import com.zipick.global.error.ErrorCode;
import com.zipick.user.entity.User;
import com.zipick.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final TokenService tokenService;
    private final EmailVerificationService emailVerification;

    @Transactional
    public SignupResponse signup(SignupRequest request) {

        if (userRepository.existsByEmail(request.email())) {
            throw new BusinessException(ErrorCode.DUPLICATE_EMAIL);
        }
        // 이메일 인증이 켜진 서버면 인증을 마친 이메일만 가입할 수 있다
        emailVerification.assertVerified(request.email(), request.verificationToken());

        User user = userRepository.save(request.toUser(passwordEncoder.encode(request.password())));
        emailVerification.consume(request.email());

        log.info("[AUTH] 회원가입 성공 userId: {}", user.getId());

        return SignupResponse.from(user);
    }

    @Transactional
    public TokenService.TokenPair login(LoginRequest request) {

        try {
            // 비밀번호 비교는 AuthenticationManager에 맡긴다.
            Authentication authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.email(), request.password())
            );

            User user = ((CustomUserDetails) authentication.getPrincipal()).getUser();

            TokenService.TokenPair tokenPair = tokenService.issueToken(user);

            log.info("[AUTH] 로그인 성공 userId: {}", user.getId());

            return tokenPair;

        } catch (SocialAccountLoginException e) {
            // 카카오로 가입한 계정이다. 비밀번호를 몇 번 다시 입력해도 성공할 수 없으므로 원인을 알린다.
            // (가입 API가 DUPLICATE_EMAIL로 이미 가입 여부를 알려주고 있어 노출되는 정보가 늘지 않는다)
            log.info("[AUTH] 소셜 계정의 비밀번호 로그인 시도 provider: {}", e.getProvider());
            throw new BusinessException(ErrorCode.SOCIAL_ACCOUNT_LOGIN);

        } catch (AuthenticationException e) {
            // 이메일이 없는 경우와 비밀번호가 틀린 경우를 구분해 알리지 않는다.
            throw new BusinessException(ErrorCode.INVALID_LOGIN);
        }
    }
}
