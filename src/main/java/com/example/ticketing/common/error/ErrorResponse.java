package com.example.ticketing.common.error;

import java.util.Map;

public record ErrorResponse(String code, String message, Map<String, Object> detail) {

    public static ErrorResponse of(ErrorCode code, Map<String, Object> detail) {
        return new ErrorResponse(code.name(), code.getDefaultMessage(), detail);
    }
}
