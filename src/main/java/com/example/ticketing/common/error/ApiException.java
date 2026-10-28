package com.example.ticketing.common.error;

import java.util.Map;
import lombok.Getter;

@Getter
public class ApiException extends RuntimeException {

    private final ErrorCode code;
    private final Map<String, Object> detail;

    public ApiException(ErrorCode code) {
        this(code, Map.of());
    }

    public ApiException(ErrorCode code, Map<String, Object> detail) {
        super(code.getDefaultMessage());
        this.code = code;
        this.detail = detail == null ? Map.of() : Map.copyOf(detail);
    }
}
