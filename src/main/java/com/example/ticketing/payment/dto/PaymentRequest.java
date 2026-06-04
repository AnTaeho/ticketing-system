package com.example.ticketing.payment.dto;

import jakarta.validation.constraints.NotNull;

public record PaymentRequest(@NotNull Long reservationId) {
}
