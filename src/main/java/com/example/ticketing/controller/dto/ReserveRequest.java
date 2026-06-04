package com.example.ticketing.controller.dto;

import jakarta.validation.constraints.NotNull;

public record ReserveRequest(@NotNull Long userId) {
}
