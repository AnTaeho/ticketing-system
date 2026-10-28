package com.example.ticketing.hold.repository;

import com.example.ticketing.hold.domain.ClaimedHold;
import com.example.ticketing.hold.domain.LiveGateSeat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class SeatHoldJdbcRepository {

    // IN 목록 하나에 넣는 토큰 수
    private static final int IN_CHUNK = 500;

    private static final String CLAIM_EXPIRED_SQL = """
            SELECT id, performance_id, user_id, hold_token FROM seat_hold
             WHERE status = 'HELD' AND expires_at < NOW(6)
             ORDER BY id LIMIT :batch
             FOR UPDATE SKIP LOCKED""";

    // 결제 테이블은 읽기만 한다. 결제 기능이 hold 기능을 부르므로 결제 서비스를 부르면 순환이 된다
    private static final String PAYMENT_ID_SQL = "SELECT id FROM booking_payment WHERE hold_id = :holdId";

    private static final String LIVE_SEAT_SQL = """
            SELECT h.hold_token, s.id AS seat_id
              FROM seat_hold h
              JOIN seat s ON s.hold_id = h.id
             WHERE h.performance_id = :pid
               AND h.hold_token IN (:tokens)
               AND s.performance_id = :pid
               AND s.status IN ('HELD', 'SOLD')""";

    private static final String LIVE_USER_HOLD_SQL = """
            SELECT hold_token
              FROM seat_hold
             WHERE performance_id = :pid
               AND hold_token IN (:tokens)
               AND status IN ('HELD', 'PAYING')""";

    private final NamedParameterJdbcTemplate jdbcTemplate;

    // 트랜잭션 밖의 SKIP LOCKED는 락이 바로 풀려 서버끼리 같은 행을 집으므로 상태 변경과 같은 트랜잭션에서만 부른다
    @Transactional(propagation = Propagation.MANDATORY)
    public List<ClaimedHold> claimExpired(int batch) {
        return jdbcTemplate.query(CLAIM_EXPIRED_SQL, Map.of("batch", batch),
                (rs, i) -> new ClaimedHold(rs.getLong("id"), rs.getLong("user_id"), rs.getLong("performance_id"),
                        rs.getString("hold_token")));
    }

    public Optional<Long> findPaymentIdByHoldId(long holdId) {
        return jdbcTemplate.queryForList(PAYMENT_ID_SQL, Map.of("holdId", holdId), Long.class).stream().findFirst();
    }

    public List<LiveGateSeat> findLiveGateSeats(long performanceId, List<String> holdTokens) {
        List<LiveGateSeat> live = new ArrayList<>();
        forEachChunk(holdTokens, chunk -> jdbcTemplate.query(LIVE_SEAT_SQL, params(performanceId, chunk),
                rs -> {
                    live.add(new LiveGateSeat(rs.getString("hold_token"), rs.getLong("seat_id")));
                }));
        return live;
    }

    // HELD·PAYING hold의 토큰만 userhold 키가 살아 있다
    public List<String> findLiveUserHoldTokens(long performanceId, List<String> holdTokens) {
        List<String> live = new ArrayList<>();
        forEachChunk(holdTokens, chunk -> live.addAll(
                jdbcTemplate.queryForList(LIVE_USER_HOLD_SQL, params(performanceId, chunk), String.class)));
        return live;
    }

    private static void forEachChunk(List<String> tokens, Consumer<List<String>> action) {
        for (int from = 0; from < tokens.size(); from += IN_CHUNK) {
            action.accept(tokens.subList(from, Math.min(from + IN_CHUNK, tokens.size())));
        }
    }

    private static MapSqlParameterSource params(long performanceId, Collection<String> tokens) {
        return new MapSqlParameterSource().addValue("pid", performanceId).addValue("tokens", tokens);
    }
}
