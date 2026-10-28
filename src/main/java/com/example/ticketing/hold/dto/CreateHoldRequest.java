package com.example.ticketing.hold.dto;

import java.util.List;

public record CreateHoldRequest(List<Long> seatIds, String queueToken) {
}
