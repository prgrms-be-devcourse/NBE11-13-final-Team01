package com.zipick.global.auth;

import com.zipick.auth.userdetails.CustomUserDetails;
import com.zipick.global.error.BusinessException;
import com.zipick.global.error.ErrorCode;
import org.springframework.core.MethodParameter;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * 현재 사용자 식별.
 *
 * TokenAuthenticationFilter가 Access Token을 검증해 SecurityContext에 담아 둔 사용자를 읽는다.
 * 임시로 쓰던 X-User-Id 헤더 방식을 대체했으며, @CurrentUser를 쓰는 컨트롤러는 수정할 필요가 없다.
 */
@Component
public class CurrentUserArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CurrentUser.class)
                && Long.class.isAssignableFrom(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication != null && authentication.getPrincipal() instanceof CustomUserDetails principal) {
            return principal.getUser().getId();
        }

        CurrentUser annotation = parameter.getParameterAnnotation(CurrentUser.class);

        if (annotation != null && annotation.required()) {
            throw new BusinessException(ErrorCode.AUTH_REQUIRED);
        }

        return null;
    }
}
