package com.example.ticketing.controller.dto;

import com.example.ticketing.domain.PaymentStatus;

public record PaymentResponse(Long paymentId, PaymentStatus status) {
}
