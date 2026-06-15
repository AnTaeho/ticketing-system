package com.example.ticketing.waitingroom.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record ReserveRequest(
        @NotNull Long userId,
        @NotBlank String queueToken
) {}
