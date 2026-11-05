package com.example.ticketing.seat.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

// 좌석 화면이 부를 때마다 DB에서 만든다. 회차가 없으면 404다
@Service
@RequiredArgsConstructor
public class SeatMapQueryService {

    private final SeatCatalog seatCatalog;
    private final SeatMapBuilder builder;

    public String get(long performanceId) {
        seatCatalog.performance(performanceId);
        return builder.build(performanceId);
    }
}
