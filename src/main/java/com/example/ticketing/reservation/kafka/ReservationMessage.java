package com.example.ticketing.reservation.kafka;

public record ReservationMessage(Long concertId, Long userId, String ticketToken) {
}
