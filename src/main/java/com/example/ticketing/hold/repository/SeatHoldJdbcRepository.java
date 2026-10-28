package com.example.ticketing.hold.repository;

import com.example.ticketing.hold.domain.ClaimedHold;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class SeatHoldJdbcRepository {

    private static final String CLAIM_EXPIRED_SQL = """
            SELECT id, performance_id, user_id, hold_token FROM seat_hold
             WHERE status = 'HELD' AND expires_at < NOW(6)
             ORDER BY id LIMIT :batch
             FOR UPDATE SKIP LOCKED""";

    private final NamedParameterJdbcTemplate jdbcTemplate;

    // 트랜잭션 밖의 SKIP LOCKED는 락이 바로 풀려 서버끼리 같은 행을 집으므로 상태 변경과 같은 트랜잭션에서만 부른다
    @Transactional(propagation = Propagation.MANDATORY)
    public List<ClaimedHold> claimExpired(int batch) {
        return jdbcTemplate.query(CLAIM_EXPIRED_SQL, Map.of("batch", batch),
                (rs, i) -> new ClaimedHold(rs.getLong("id"), rs.getLong("user_id"), rs.getLong("performance_id"),
                        rs.getString("hold_token")));
    }
}
