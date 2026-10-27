package com.example.ticketing.booking.common;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

// 전역 처리기의 Exception 처리기보다 먼저 잡혀야 예매 응답 형식이 유지된다
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackages = "com.example.ticketing.booking")
public class BookingExceptionHandler {

    private static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    @ExceptionHandler(BookingException.class)
    public ResponseEntity<BookingErrorResponse> handleBooking(BookingException e) {
        return respond(e.getCode(), e.getDetail());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<BookingErrorResponse> handleValidation(MethodArgumentNotValidException e) {
        Map<String, Object> fields = new LinkedHashMap<>();
        for (FieldError error : e.getBindingResult().getFieldErrors()) {
            fields.putIfAbsent(error.getField(), String.valueOf(error.getDefaultMessage()));
        }
        return respond(BookingErrorCode.INVALID_REQUEST, Map.of("fields", fields));
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<BookingErrorResponse> handleMissingHeader(MissingRequestHeaderException e) {
        if (IDEMPOTENCY_KEY_HEADER.equalsIgnoreCase(e.getHeaderName())) {
            return respond(BookingErrorCode.IDEMPOTENCY_KEY_REQUIRED, Map.of());
        }
        return respond(BookingErrorCode.INVALID_REQUEST, Map.of("header", e.getHeaderName()));
    }

    private ResponseEntity<BookingErrorResponse> respond(BookingErrorCode code, Map<String, Object> detail) {
        return ResponseEntity.status(code.getStatus()).body(BookingErrorResponse.of(code, detail));
    }
}
