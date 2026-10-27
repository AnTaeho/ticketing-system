package com.example.ticketing.booking.payment.api;

import com.example.ticketing.booking.common.CurrentUser;
import com.example.ticketing.booking.payment.BookingPaymentService;
import com.example.ticketing.booking.payment.dto.PaymentResponse;
import com.example.ticketing.booking.payment.dto.PaymentStatusView;
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

    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private final BookingPaymentService paymentService;
    private final PgScenarioHeaderReader scenarioHeaderReader;

    // 확정은 200, 결과를 아직 모르면 202. 실패는 예매 예외 처리기가 402·409로 바꾼다
    @PostMapping("/api/holds/{holdId}/payments")
    public ResponseEntity<PaymentResponse> pay(@CurrentUser Long userId, @PathVariable long holdId,
                                               @RequestHeader(IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
                                               @RequestHeader(value = PgScenarioHeaderReader.HEADER, required = false)
                                               String scenarioHeader,
                                               @RequestBody(required = false) PayRequest request) {
        PaymentResponse response = paymentService.pay(userId, holdId, idempotencyKey,
                scenarioHeaderReader.read(scenarioHeader));
        HttpStatus status = response.status() == PaymentResponse.Status.CONFIRMED ? HttpStatus.OK : HttpStatus.ACCEPTED;
        return ResponseEntity.status(status).body(response);
    }

    @GetMapping("/api/payments/{paymentId}")
    public PaymentStatusView get(@CurrentUser Long userId, @PathVariable long paymentId) {
        return paymentService.getPayment(userId, paymentId);
    }
}
