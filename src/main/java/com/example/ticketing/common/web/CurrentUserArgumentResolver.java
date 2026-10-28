package com.example.ticketing.common.web;

import com.example.ticketing.common.error.ApiException;
import com.example.ticketing.common.error.ErrorCode;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

public class CurrentUserArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        Class<?> type = parameter.getParameterType();
        return parameter.hasParameterAnnotation(CurrentUser.class)
                && (type == Long.class || type == long.class);
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        String header = webRequest.getHeader(HttpNames.USER_ID_HEADER);
        if (header != null && !header.isBlank()) {
            return parseUserId(header);
        }
        HttpServletRequest request = webRequest.getNativeRequest(HttpServletRequest.class);
        if (request != null && request.getCookies() != null) {
            for (Cookie cookie : request.getCookies()) {
                if (HttpNames.USER_ID_COOKIE.equals(cookie.getName())) {
                    return parseUserId(cookie.getValue());
                }
            }
        }
        throw new ApiException(ErrorCode.LOGIN_REQUIRED);
    }

    private Long parseUserId(String raw) {
        try {
            long userId = Long.parseLong(raw.trim());
            if (userId > 0) {
                return userId;
            }
        } catch (NumberFormatException ignored) {
        }
        throw new ApiException(ErrorCode.LOGIN_REQUIRED);
    }
}
