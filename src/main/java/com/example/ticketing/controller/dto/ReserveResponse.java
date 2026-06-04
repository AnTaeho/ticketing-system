package com.example.ticketing.controller.dto;

import com.example.ticketing.domain.ReservationStatus;

public record ReserveResponse(Long reservationId, ReservationStatus status) {
}
