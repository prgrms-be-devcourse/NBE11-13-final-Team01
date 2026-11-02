package com.zipick.auth.service;

import com.zipick.global.error.BusinessException;
import com.zipick.global.error.ErrorCode;
import com.zipick.user.entity.User;
import com.zipick.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 카카오 신규 계정 저장만 담당한다. 별도 클래스인 이유는 <b>트랜잭션 경계</b> 때문이다.
 *
 * <p>같은 카카오 계정으로 동시에 처음 로그인하면 두 요청이 모두 "없음" 조회를 통과해 INSERT 를 시도하고,
 * 한쪽이 {@code uk_users_provider} 유일 제약에 걸린다. 이때 진 쪽은 이긴 쪽이 만든 행을 다시 읽어 써야 한다.
 *
 * <p>그런데 <b>PostgreSQL 은 트랜잭션 안에서 문장 하나가 실패하면 그 트랜잭션 전체를 중단 상태로 만든다.</b>
 * 이후의 모든 질의가 {@code 25P02 current transaction is aborted} 로 거부된다.
 * JPA 도 flush 가 실패하면 영속성 컨텍스트를 신뢰할 수 없다고 보고 트랜잭션을 rollback-only 로 표시한다.
 * 따라서 <b>같은 트랜잭션 안에서 잡아 재조회하는 방식은 PostgreSQL 에서 동작하지 않는다.</b>
 *
 * <p>그래서 저장을 REQUIRES_NEW 로 분리한다. 제약 위반은 이 안쪽 트랜잭션만 중단시키고,
 * 예외가 바깥으로 나올 때는 안쪽 트랜잭션이 이미 롤백되어 끝나 있다.
 * 호출하는 쪽의 연결은 깨끗하므로 그때 다시 조회할 수 있다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KakaoUserRegistrar {

    private final UserRepository userRepository;

    /**
     * 새 카카오 계정을 자기 트랜잭션에서 저장한다.
     *
     * <p>이메일 중복 검사와 INSERT 사이에도 경쟁은 남는다(검사 후 다른 요청이 그 이메일로 가입).
     * 그 경우는 {@code uk_users_email} 이 막고 호출하는 쪽이 결과를 해석한다.
     *
     * @throws BusinessException            검증된 카카오 이메일이 이미 가입된 이메일일 때
     * @throws org.springframework.dao.DataIntegrityViolationException 유일 제약에 걸렸을 때
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public User register(String providerId, String verifiedEmail) {

        // 검증된 카카오 이메일이 기존 계정의 이메일과 같으면 새 계정을 만들지 않는다.
        // 자동 연결도 하지 않는다 (계정 연동은 범위 밖이다). 기존 로그인 수단을 쓰도록 안내한다.
        if (verifiedEmail != null && userRepository.existsByEmail(verifiedEmail)) {
            log.info("[AUTH] 카카오 이메일이 기존 계정과 충돌해 가입을 중단했습니다");
            throw new BusinessException(ErrorCode.SOCIAL_EMAIL_ALREADY_REGISTERED);
        }

        // saveAndFlush 로 INSERT 를 지금 보낸다. save 만 하면 트랜잭션 커밋 시점에 나가서
        // 제약 위반이 이 메서드 밖(커밋 단계)에서 터지고, 아래 호출자의 복구 분기가 받지 못한다.
        User created = userRepository.saveAndFlush(User.ofKakao(providerId, verifiedEmail));

        log.info("[AUTH] 카카오 신규 가입 userId: {}, email 제공 여부: {}", created.getId(), verifiedEmail != null);

        return created;
    }
}
