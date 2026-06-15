package com.example.ticketing.concert.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

public record ConcertCreateRequest(
        @NotBlank String title,
        @Positive int stock
) {}
