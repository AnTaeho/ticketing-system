package com.example.ticketing.booking.hold;

import com.example.ticketing.booking.seat.SeatRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

// 집기와 상태 변경을 한 트랜잭션에서 한다. 트랜잭션 밖의 SKIP LOCKED는 락이 바로 풀려 서버끼리 같은 행을 집는다
@Component
@RequiredArgsConstructor
public class HoldExpiryTransaction {

    private static final String CLAIM_SQL = """
            SELECT id, performance_id, user_id, hold_token FROM seat_hold
             WHERE status = 'HELD' AND expires_at < NOW(6)
             ORDER BY id LIMIT :batch
             FOR UPDATE SKIP LOCKED""";

    private static final String HELD_SEATS_SQL = """
            SELECT id, hold_id FROM seat
             WHERE hold_id IN (:ids) AND status = 'HELD'
             ORDER BY id""";

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final SeatHoldRepository seatHoldRepository;
    private final SeatRepository seatRepository;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public List<ExpiredHold> claimAndExpire(int batch) {
        List<ClaimedHold> claimed = jdbcTemplate.query(CLAIM_SQL, Map.of("batch", batch),
                (rs, i) -> new ClaimedHold(rs.getLong("id"), rs.getLong("performance_id"),
                        rs.getLong("user_id"), rs.getString("hold_token")));
        if (claimed.isEmpty()) {
            return List.of();
        }
        List<Long> ids = claimed.stream().map(ClaimedHold::holdId).toList();

        Map<Long, List<Long>> seatsByHold = new HashMap<>();
        jdbcTemplate.query(HELD_SEATS_SQL, Map.of("ids", ids), rs -> {
            seatsByHold.computeIfAbsent(rs.getLong("hold_id"), k -> new ArrayList<>()).add(rs.getLong("id"));
        });

        seatHoldRepository.markExpired(ids);
        seatRepository.releaseHeldSeatsOf(ids);

        return claimed.stream()
                .map(h -> new ExpiredHold(h.holdId(), h.performanceId(), h.userId(), h.holdToken(),
                        List.copyOf(seatsByHold.getOrDefault(h.holdId(), List.of()))))
                .toList();
    }

    private record ClaimedHold(long holdId, long performanceId, long userId, String holdToken) {
    }

    // seatIds는 이 hold가 아직 쥐고 있던 좌석만이다. 남이 가져간 좌석은 빠진다
    public record ExpiredHold(long holdId, long performanceId, long userId, String holdToken, List<Long> seatIds) {
    }
}
