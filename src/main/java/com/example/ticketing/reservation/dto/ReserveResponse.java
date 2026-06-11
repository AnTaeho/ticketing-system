package com.example.ticketing.reservation.dto;

import com.example.ticketing.reservation.domain.ReservationStatus;

public record ReserveResponse(Long reservationId, ReservationStatus status, String ticketToken) {

    public ReserveResponse(Long reservationId, ReservationStatus status) {
        this(reservationId, status, null);
    }
}
