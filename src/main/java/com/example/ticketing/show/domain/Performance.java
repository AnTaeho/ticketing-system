package com.example.ticketing.show.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "performance", indexes = {
        @Index(name = "idx_perf_show", columnList = "show_id, starts_at"),
        @Index(name = "idx_perf_open", columnList = "open_at, starts_at")
})
public class Performance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "performance_id")
    private Long id;

    @Column(name = "show_id", nullable = false)
    private Long showId;

    @Column(name = "starts_at", nullable = false)
    private LocalDateTime startsAt;

    @Column(name = "open_at", nullable = false)
    private LocalDateTime openAt;

    public static Performance create(Long showId, LocalDateTime startsAt, LocalDateTime openAt) {
        Performance performance = new Performance();
        performance.showId = showId;
        performance.startsAt = startsAt;
        performance.openAt = openAt;
        return performance;
    }
}
