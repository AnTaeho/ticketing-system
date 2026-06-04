package com.example.ticketing.reservation.dto;

import com.example.ticketing.reservation.domain.ReservationStatus;

public record AsyncReserveResponse(String ticketToken, ReservationStatus status) {
}
