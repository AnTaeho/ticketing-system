package com.example.ticketing.booking.show.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum SeatGrade {
    VIP(180_000),
    R(150_000),
    S(120_000),
    A(90_000);

    private final int price;
}
