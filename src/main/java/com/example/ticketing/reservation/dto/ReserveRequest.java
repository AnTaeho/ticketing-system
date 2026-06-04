package com.example.ticketing.reservation.dto;

import jakarta.validation.constraints.NotNull;

public record ReserveRequest(@NotNull Long userId) {
}
