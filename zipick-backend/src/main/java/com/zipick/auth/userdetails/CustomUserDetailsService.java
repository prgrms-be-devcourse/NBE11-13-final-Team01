package com.zipick.auth.userdetails;

import com.zipick.user.entity.User;
import com.zipick.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    @Override
    public CustomUserDetails loadUserByUsername(String email) throws UsernameNotFoundException {

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("사용자를 찾을 수 없습니다."));

        // 소셜 계정은 비밀번호가 없다. 비교 단계까지 가면 BadCredentials 로 뭉개져
        // 사용자가 비밀번호를 계속 다시 입력하게 되므로 여기서 원인을 구분한다.
        if (!user.isLocalAccount()) {
            throw new SocialAccountLoginException(user.getProvider());
        }

        return new CustomUserDetails(user);
    }
}
