package com.example.ticketing.outbox.domain;

import java.util.Arrays;
import java.util.Optional;

// aggregate_id: TICKET_ISSUED = booking_id, REFUND_REQUESTED = payment_id
public enum OutboxTaskType {
    TICKET_ISSUED,
    REFUND_REQUESTED;

    // enum 밖 값은 DB에 잘못 들어간 행이나 새 버전이 쓴 행뿐이다
    public static Optional<OutboxTaskType> parse(String name) {
        return Arrays.stream(values()).filter(type -> type.name().equals(name)).findFirst();
    }
}
