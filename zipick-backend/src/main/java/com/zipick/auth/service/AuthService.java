package com.zipick.auth.service;

import com.zipick.auth.dto.request.LoginRequest;
import com.zipick.auth.dto.request.SignupRequest;
import com.zipick.auth.dto.response.SignupResponse;
import com.zipick.auth.userdetails.CustomUserDetails;
import com.zipick.user.entity.User;
import com.zipick.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final TokenService tokenService;

    @Transactional
    public SignupResponse signup(SignupRequest request) {

        if (userRepository.existsByEmail(request.email())) {
            // TODO 공통 예외 처리 구조가 마련되면 BusinessException 등으로 교체한다.
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 사용 중인 이메일입니다.");
        }

        User user = userRepository.save(request.toUser(passwordEncoder.encode(request.password())));

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

        } catch (AuthenticationException e) {
            // 이메일이 없는 경우와 비밀번호가 틀린 경우를 구분해 알리지 않는다.
            // TODO 공통 예외 처리 구조가 마련되면 BusinessException 등으로 교체한다.
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다.");
        }
    }
}
