package com.example.ticketing.outbox.dto;

public record TicketIssuedPayload(long bookingId) implements OutboxPayload {
}
