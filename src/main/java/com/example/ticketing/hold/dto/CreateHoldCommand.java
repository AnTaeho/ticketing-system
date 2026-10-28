package com.example.ticketing.hold.dto;

import java.util.List;

public record CreateHoldCommand(long userId, long performanceId, List<Long> seatIds, String queueToken) {
}
