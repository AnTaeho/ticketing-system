package com.example.ticketing.payment.controller;

import com.example.ticketing.common.web.CurrentUser;
import com.example.ticketing.common.web.HttpNames;
import com.example.ticketing.payment.dto.PayRequest;
import com.example.ticketing.payment.dto.PaymentOutcome;
import com.example.ticketing.payment.dto.PaymentResponse;
import com.example.ticketing.payment.dto.PaymentResult;
import com.example.ticketing.payment.dto.PaymentStatusResponse;
import com.example.ticketing.payment.service.PaymentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class PaymentApiController {

    private final PaymentService paymentService;
    private final PaymentHeaderReader headerReader;

    // 확정은 200, 결과를 아직 모르면 202. 실패는 예외 처리기가 402·409로 바꾼다
    @PostMapping("/api/holds/{holdId}/payments")
    public ResponseEntity<PaymentResponse> pay(
            @CurrentUser Long userId, @PathVariable long holdId,
            @RequestHeader(value = HttpNames.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestHeader(value = HttpNames.PG_SCENARIO_HEADER, required = false) String scenarioHeader,
            @RequestBody(required = false) PayRequest body) {
        PaymentResult result = paymentService.pay(headerReader.toCommand(userId, holdId, idempotencyKey,
                scenarioHeader));
        HttpStatus status = result.outcome() == PaymentOutcome.CONFIRMED ? HttpStatus.OK : HttpStatus.ACCEPTED;
        return ResponseEntity.status(status).body(PaymentResponse.from(result));
    }

    @GetMapping("/api/payments/{paymentId}")
    public PaymentStatusResponse get(@CurrentUser Long userId, @PathVariable long paymentId) {
        return PaymentStatusResponse.from(paymentService.getPayment(userId, paymentId));
    }
}
