package com.example.ticketing.show.dto;

public record CreateShowResponse(Long showId) {

    public static CreateShowResponse from(CreateShowResult result) {
        return new CreateShowResponse(result.showId());
    }
}
