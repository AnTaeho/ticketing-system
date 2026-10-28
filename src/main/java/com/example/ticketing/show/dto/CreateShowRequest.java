package com.example.ticketing.show.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateShowRequest(
        @NotBlank @Size(max = 200) String title,
        @NotBlank @Size(max = 100) String venueName,
        @Size(max = 1000) String description
) {
}
