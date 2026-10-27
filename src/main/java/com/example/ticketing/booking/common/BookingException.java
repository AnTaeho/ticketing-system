package com.example.ticketing.booking.common;

import java.util.Map;
import lombok.Getter;

@Getter
public class BookingException extends RuntimeException {

    private final BookingErrorCode code;
    private final Map<String, Object> detail;

    public BookingException(BookingErrorCode code) {
        this(code, Map.of());
    }

    public BookingException(BookingErrorCode code, Map<String, Object> detail) {
        super(code.getDefaultMessage());
        this.code = code;
        this.detail = detail == null ? Map.of() : Map.copyOf(detail);
    }
}
