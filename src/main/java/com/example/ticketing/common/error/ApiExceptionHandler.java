package com.example.ticketing.common.error;

import com.example.ticketing.common.web.HttpNames;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

// @RestController만 맡고 화면 컨트롤러(@Controller)는 화면 처리기에 맡긴다. 여기서 안 잡는 예외는 Boot 기본 오류 응답으로 간다
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
@RestControllerAdvice(annotations = RestController.class)
public class ApiExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleBooking(ApiException e) {
        return respond(e.getCode(), e.getDetail());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e) {
        Map<String, Object> fields = new LinkedHashMap<>();
        for (FieldError error : e.getBindingResult().getFieldErrors()) {
            fields.putIfAbsent(error.getField(), String.valueOf(error.getDefaultMessage()));
        }
        return respond(ErrorCode.INVALID_REQUEST, Map.of("fields", fields));
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ErrorResponse> handleMissingHeader(MissingRequestHeaderException e) {
        if (HttpNames.IDEMPOTENCY_KEY_HEADER.equalsIgnoreCase(e.getHeaderName())) {
            return respond(ErrorCode.IDEMPOTENCY_KEY_REQUIRED, Map.of());
        }
        return respond(ErrorCode.INVALID_REQUEST, Map.of("header", e.getHeaderName()));
    }

    // 날짜 형식이 틀린 본문이나 숫자가 아닌 경로 변수도 예매 형식의 400으로 답한다
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException e) {
        return respond(ErrorCode.INVALID_REQUEST, Map.of("reason", "요청 본문을 읽지 못했다"));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return respond(ErrorCode.INVALID_REQUEST, Map.of("parameter", e.getName()));
    }

    private ResponseEntity<ErrorResponse> respond(ErrorCode code, Map<String, Object> detail) {
        return ResponseEntity.status(code.getStatus()).body(ErrorResponse.of(code, detail));
    }
}
