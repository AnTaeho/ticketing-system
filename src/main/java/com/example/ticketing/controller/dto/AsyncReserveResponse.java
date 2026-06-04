package com.example.ticketing.controller.dto;

import com.example.ticketing.domain.ReservationStatus;

public record AsyncReserveResponse(String ticketToken, ReservationStatus status) {
}
