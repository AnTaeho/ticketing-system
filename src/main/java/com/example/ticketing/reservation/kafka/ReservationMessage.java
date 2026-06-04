package com.example.ticketing.reservation.kafka;

public record ReservationMessage(String ticketToken, Long concertId, Long userId) {
}
