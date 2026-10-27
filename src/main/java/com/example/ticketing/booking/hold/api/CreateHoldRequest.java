package com.example.ticketing.booking.hold.api;

import java.util.List;

public record CreateHoldRequest(List<Long> seatIds, String queueToken) {
}
