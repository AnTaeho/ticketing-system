package com.example.ticketing.booking.show;

import com.example.ticketing.booking.show.domain.Performance;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PerformanceRepository extends JpaRepository<Performance, Long> {

    List<Performance> findByShowIdOrderByStartsAtAsc(Long showId);
}
