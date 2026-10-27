package com.example.ticketing.booking.show;

import com.example.ticketing.booking.show.domain.Performance;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PerformanceRepository extends JpaRepository<Performance, Long> {

    List<Performance> findByShowIdOrderByStartsAtAsc(Long showId);

    // 공연마다 아직 시작하지 않은 가장 이른 회차. 같은 시각 회차가 여럿이면 모두 돌려준다
    @Query(value = """
            SELECT p.* FROM performance p
            JOIN (SELECT show_id, MIN(starts_at) AS first_starts_at
                    FROM performance
                   WHERE starts_at > :now
                   GROUP BY show_id) n
              ON n.show_id = p.show_id AND n.first_starts_at = p.starts_at
            """, nativeQuery = true)
    List<Performance> findNextPerformances(@Param("now") LocalDateTime now);
}
