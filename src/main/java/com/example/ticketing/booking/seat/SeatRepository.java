package com.example.ticketing.booking.seat;

import com.example.ticketing.booking.seat.domain.Seat;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SeatRepository extends JpaRepository<Seat, Long> {

    List<Seat> findByPerformanceId(Long performanceId);

    long countByPerformanceId(Long performanceId);
}
