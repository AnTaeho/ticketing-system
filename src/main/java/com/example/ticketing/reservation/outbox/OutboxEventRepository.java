package com.example.ticketing.reservation.outbox;

import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    List<OutboxEvent> findByStatusAndCreatedAtBefore(OutboxStatus status, LocalDateTime threshold);

    long countByStatus(OutboxStatus status);
}
