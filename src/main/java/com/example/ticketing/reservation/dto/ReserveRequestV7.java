package com.example.ticketing.reservation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record ReserveRequestV7(
        @NotNull Long userId,
        @NotBlank String queueToken
) {}
