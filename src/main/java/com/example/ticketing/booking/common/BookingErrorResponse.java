package com.example.ticketing.booking.common;

import java.util.Map;

public record BookingErrorResponse(String code, String message, Map<String, Object> detail) {

    public static BookingErrorResponse of(BookingErrorCode code, Map<String, Object> detail) {
        return new BookingErrorResponse(code.name(), code.getDefaultMessage(), detail);
    }
}
