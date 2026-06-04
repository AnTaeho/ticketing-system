package com.example.ticketing.payment.controller;

import com.example.ticketing.payment.dto.PaymentRequest;
import com.example.ticketing.payment.dto.PaymentResponse;
import com.example.ticketing.payment.domain.Payment;
import com.example.ticketing.payment.service.PaymentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    @PostMapping
    public ResponseEntity<PaymentResponse> pay(@RequestBody @Valid PaymentRequest request) {
        Payment payment = paymentService.pay(request.reservationId());
        return ResponseEntity.ok(new PaymentResponse(payment.getId(), payment.getStatus()));
    }
}
