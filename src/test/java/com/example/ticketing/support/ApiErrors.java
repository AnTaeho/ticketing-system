package com.example.ticketing.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.example.ticketing.common.error.ApiException;
import com.example.ticketing.common.error.ErrorCode;
import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;

// 사용자에게 돌려줄 실패(ApiException)를 잡아 코드와 detail을 단언할 때 쓴다
public final class ApiErrors {

    private ApiErrors() {
    }

    public static ApiException catchApiException(ThrowingCallable action) {
        Throwable thrown = catchThrowable(action);
        assertThat(thrown).as("ApiException이 나야 한다").isInstanceOf(ApiException.class);
        return (ApiException) thrown;
    }

    // 동시 실행 결과의 ApiException을 코드별로 센다
    public static Map<ErrorCode, Long> countByCode(Collection<ApiException> errors) {
        return errors.stream().collect(Collectors.groupingBy(ApiException::getCode, Collectors.counting()));
    }
}
