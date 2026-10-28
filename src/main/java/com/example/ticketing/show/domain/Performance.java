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
    private Long id;

    @Column(name = "show_id", nullable = false)
    private Long showId;

    @Column(name = "starts_at", nullable = false)
    private LocalDateTime startsAt;

    @Column(name = "open_at", nullable = false)
    private LocalDateTime openAt;

    // 확정 좌석 게이트 키가 이 시각에 만료된다
    @Column(name = "gate_close_at", nullable = false)
    private LocalDateTime gateCloseAt;

    public static Performance create(Long showId, LocalDateTime startsAt, LocalDateTime openAt,
                                     LocalDateTime gateCloseAt) {
        Performance performance = new Performance();
        performance.showId = showId;
        performance.startsAt = startsAt;
        performance.openAt = openAt;
        performance.gateCloseAt = gateCloseAt;
        return performance;
    }
}
