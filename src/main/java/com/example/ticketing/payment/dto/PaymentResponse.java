package com.example.ticketing.payment.dto;

import com.example.ticketing.payment.domain.PaymentStatus;

public record PaymentResponse(Long paymentId, PaymentStatus status) {
}
