package com.example.ticketing.payment.controller;

import com.example.ticketing.common.error.ApiException;
import com.example.ticketing.common.error.ErrorCode;
import com.example.ticketing.common.web.CurrentUser;
import com.example.ticketing.common.web.DemoMode;
import com.example.ticketing.common.web.HttpNames;
import com.example.ticketing.payment.dto.PayCommand;
import com.example.ticketing.payment.dto.PayRequest;
import com.example.ticketing.payment.dto.PaymentOutcome;
import com.example.ticketing.payment.dto.PaymentResponse;
import com.example.ticketing.payment.dto.PaymentResult;
import com.example.ticketing.payment.dto.PaymentStatusResponse;
import com.example.ticketing.payment.dto.PgScenario;
import com.example.ticketing.payment.service.PaymentReader;
import com.example.ticketing.payment.service.PaymentService;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

// 결제 요청 헤더의 형식 검증은 여기서만 한다. 시연용 시나리오 헤더는 prod에서 값이 있어도 무시한다
@RestController
public class PaymentApiController {

    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 64;

    private final PaymentService paymentService;
    private final PaymentReader paymentReader;
    private final boolean demo;

    public PaymentApiController(PaymentService paymentService, PaymentReader paymentReader, DemoMode demoMode) {
        this.paymentService = paymentService;
        this.paymentReader = paymentReader;
        this.demo = demoMode.enabled();
    }

    // 확정은 200, 결과를 아직 모르면 202. 실패는 예외 처리기가 402·409로 바꾼다
    @PostMapping("/api/holds/{holdId}/payments")
    public ResponseEntity<PaymentResponse> pay(
            @CurrentUser Long userId, @PathVariable long holdId,
            @RequestHeader(value = HttpNames.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestHeader(value = HttpNames.PG_SCENARIO_HEADER, required = false) String scenarioHeader,
            @RequestBody(required = false) PayRequest body) {
        PaymentResult result = paymentService.pay(
                new PayCommand(userId, holdId, idempotencyKey(idempotencyKey), scenario(scenarioHeader)));
        HttpStatus status = result.outcome() == PaymentOutcome.CONFIRMED ? HttpStatus.OK : HttpStatus.ACCEPTED;
        return ResponseEntity.status(status).body(PaymentResponse.from(result));
    }

    @GetMapping("/api/payments/{paymentId}")
    public PaymentStatusResponse get(@CurrentUser Long userId, @PathVariable long paymentId) {
        return PaymentStatusResponse.from(paymentReader.find(userId, paymentId));
    }

    private static String idempotencyKey(String header) {
        if (header == null || header.isBlank()) {
            throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_REQUIRED);
        }
        String key = header.trim();
        if (key.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    Map.of("header", HttpNames.IDEMPOTENCY_KEY_HEADER, "maxLength", MAX_IDEMPOTENCY_KEY_LENGTH));
        }
        return key;
    }

    private Optional<PgScenario> scenario(String header) {
        if (!demo || header == null || header.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(PgScenario.parse(header).orElseThrow(() -> new ApiException(ErrorCode.INVALID_REQUEST,
                Map.of("header", HttpNames.PG_SCENARIO_HEADER, "value", header.trim()))));
    }
}
