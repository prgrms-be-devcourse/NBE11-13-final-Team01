package com.zipick.global.auth;

import com.zipick.global.error.BusinessException;
import com.zipick.global.error.ErrorCode;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * 현재 사용자 식별 — 임시 구현.
 *
 * A 담당(카카오 로그인·JWT)이 완성되면 이 리졸버만 JWT 파싱으로 교체한다.
 * 그 전까지는 프론트가 보내는 X-User-Id 헤더를 사용자 ID로 쓴다. (로컬 개발 전용)
 */
@Component
public class CurrentUserArgumentResolver implements HandlerMethodArgumentResolver {

    public static final String HEADER = "X-User-Id";

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CurrentUser.class)
                && Long.class.isAssignableFrom(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        CurrentUser ann = parameter.getParameterAnnotation(CurrentUser.class);
        String raw = webRequest.getHeader(HEADER);
        if (raw == null || raw.isBlank()) {
            if (ann != null && ann.required()) {
                throw new BusinessException(ErrorCode.AUTH_REQUIRED);
            }
            return null;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw new BusinessException(ErrorCode.AUTH_REQUIRED);
        }
    }
}
