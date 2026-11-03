package com.example.ticketing.show.repository;

import com.example.ticketing.show.domain.Performance;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PerformanceRepository extends JpaRepository<Performance, Long> {

    List<Performance> findByShowIdOrderByStartsAtAscIdAsc(Long showId);

    // 서버 시계나 SeatCatalog 캐시 대신 DB 시각으로 회차 시작 여부를 본다
    @Query("select count(p) from Performance p where p.id = :id and p.startsAt <= current_timestamp")
    long countStarted(@Param("id") long id);

    // 공연마다 아직 시작하지 않은 가장 이른 회차. 같은 시각 회차가 여럿이면 모두 돌려준다
    @Query("""
            select p from Performance p
             where p.startsAt = (select min(n.startsAt) from Performance n
                                  where n.showId = p.showId and n.startsAt > :now)""")
    List<Performance> findNextPerformances(@Param("now") LocalDateTime now);
}
