package com.example.ticketing.hold.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ticketing.common.config.TicketingProperties;
import com.example.ticketing.common.error.ApiException;
import com.example.ticketing.common.error.ErrorCode;
import com.example.ticketing.common.metrics.Metrics;
import com.example.ticketing.common.metrics.Metrics.Tag;
import com.example.ticketing.hold.dto.CreateHoldCommand;
import com.example.ticketing.hold.dto.CreateHoldResult;
import com.example.ticketing.support.ApiErrors;
import com.example.ticketing.support.Concurrently;
import com.example.ticketing.support.Concurrently.Outcome;
import com.example.ticketing.support.Concurrently.Results;
import com.example.ticketing.support.Counters;
import com.example.ticketing.support.Fixtures;
import com.example.ticketing.support.IntegrationTest;
import com.example.ticketing.support.TestDb;
import com.example.ticketing.support.TestRedis;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

// 게이트 ON에서 돈다. 같은 테스트를 게이트 OFF로 돌리는 것은 SeatHoldConcurrencyGateOffTest
@IntegrationTest
class SeatHoldConcurrencyTest {

    private static final int THREAD_COUNT = 500;
    private static final int CROSS_ROUNDS = 200;

    @Autowired
    private SeatHoldService seatHoldService;
    @Autowired
    private Fixtures fixtures;
    @Autowired
    private TestDb db;
    @Autowired
    private TestRedis testRedis;
    @Autowired
    private Counters counters;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private TicketingProperties properties;

    @Test
    @DisplayName("같은 2석에 500명이 동시에 잡으면 한 명만 성공한다")
    void sameSeatsFiveHundredUsers() {
        long pid = fixtures.newPerformance(1, 1, 4);
        List<Long> target = db.seatIds(pid).subList(0, 2);
        List<Long> users = new ArrayList<>(THREAD_COUNT);
        for (int i = 0; i < THREAD_COUNT; i++) {
            long userId = fixtures.newUser();
            users.add(userId);
        }
        double okBefore = counters.count(Metrics.HOLD_DB, Tag.RESULT, "ok");
        double changedBefore = counters.count(Metrics.HOLD_DB, Tag.RESULT, "state_changed");
        double dbBefore = counters.sum(Metrics.HOLD_DB);
        double gateOkBefore = counters.count(Metrics.HOLD_GATE, Tag.RESULT, "ok");
        double gateTakenBefore = counters.count(Metrics.HOLD_GATE, Tag.RESULT, "seat_taken");
        double gateSkippedBefore = counters.count(Metrics.HOLD_GATE, Tag.RESULT, "skipped");

        Results<CreateHoldResult> results = Concurrently.of(THREAD_COUNT)
                .readyWithin(Duration.ofSeconds(30))
                .within(Duration.ofSeconds(60))
                .run(i -> service().hold(new CreateHoldCommand(users.get(i), pid, target)));

        assertThat(results.unexpected(ApiException.class)).isEmpty();
        assertThat(results.values()).hasSize(1);
        ErrorCode expected = seatConflictCode();
        Map<ErrorCode, Long> rejected = ApiErrors.countByCode(results.errors(ApiException.class));
        assertThat(rejected).containsOnlyKeys(expected);
        assertThat(rejected.get(expected)).isEqualTo(THREAD_COUNT - 1L);

        long winnerHoldId = results.values().get(0).holdId();
        assertThat(db.holdCount(pid)).isEqualTo(1);
        assertThat(db.heldSeats(pid, winnerHoldId)).hasSize(2);
        assertThat(db.heldSeatCount(pid)).isEqualTo(2);
        double dbDelta = counters.sum(Metrics.HOLD_DB) - dbBefore;
        assertThat(counters.count(Metrics.HOLD_DB, Tag.RESULT, "ok") - okBefore).isEqualTo(1);
        if (gateEnabled()) {
            // 게이트가 Redis에서 거절하므로 DB에 닿는 선점은 이긴 1건뿐이다
            assertThat(dbDelta).isEqualTo(1);
            assertThat(counters.count(Metrics.HOLD_DB, Tag.RESULT, "state_changed") - changedBefore).isZero();
            assertThat(counters.count(Metrics.HOLD_GATE, Tag.RESULT, "ok") - gateOkBefore).isEqualTo(1);
            assertThat(counters.count(Metrics.HOLD_GATE, Tag.RESULT, "seat_taken") - gateTakenBefore)
                    .isEqualTo(THREAD_COUNT - 1);
        } else {
            assertThat(dbDelta).isEqualTo(THREAD_COUNT);
            assertThat(counters.count(Metrics.HOLD_DB, Tag.RESULT, "state_changed") - changedBefore)
                    .isEqualTo(THREAD_COUNT - 1);
            assertThat(counters.count(Metrics.HOLD_GATE, Tag.RESULT, "skipped") - gateSkippedBefore)
                    .isEqualTo(THREAD_COUNT);
            assertThat(testRedis.keys(pid)).isEmpty();
        }
    }

    @Test
    @DisplayName("두 사람이 같은 두 좌석을 엇갈린 순서로 200번 잡아도 데드락 없이 매번 한 명만 성공한다")
    void crossOrderNoDeadlock() {
        long pid = fixtures.newPerformance(1, 1, 2);
        ErrorCode conflict = seatConflictCode();
        List<Long> ids = db.seatIds(pid);
        List<Long> forward = List.of(ids.get(0), ids.get(1));
        List<Long> reverse = List.of(ids.get(1), ids.get(0));
        long userA = fixtures.newUser();
        long userB = fixtures.newUser();
        List<Throwable> unexpected = new ArrayList<>();
        int deadlocks = 0;
        List<Integer> badRounds = new ArrayList<>();

        for (int round = 0; round < CROSS_ROUNDS; round++) {
            Results<CreateHoldResult> results = Concurrently.of(2).within(Duration.ofSeconds(60))
                    .run(i -> i == 0
                            ? service().hold(new CreateHoldCommand(userA, pid, forward))
                            : service().hold(new CreateHoldCommand(userB, pid, reverse)));

            int conflicted = 0;
            for (Throwable error : results.errors()) {
                if (error instanceof ApiException e && e.getCode() == conflict) {
                    conflicted++;
                    continue;
                }
                if (!(error instanceof ApiException) && isDeadlock(error)) {
                    deadlocks++;
                }
                unexpected.add(error);
            }
            if (results.values().size() != 1 || conflicted != 1) {
                badRounds.add(round);
            }
            // 다음 회차를 위해 이긴 쪽의 선점을 풀어 같은 좌석을 다시 쓴다
            List<Outcome<CreateHoldResult>> outcomes = results.outcomes();
            for (int i = 0; i < outcomes.size(); i++) {
                if (!outcomes.get(i).failed()) {
                    service().release(i == 0 ? userA : userB, outcomes.get(i).value().holdId());
                }
            }
        }

        assertThat(deadlocks).isZero();
        assertThat(unexpected).isEmpty();
        assertThat(badRounds).isEmpty();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM seat_hold WHERE performance_id = ? AND status = 'RELEASED'",
                Long.class, pid)).isEqualTo(CROSS_ROUNDS);
        // 매 회 취소가 게이트 키도 지운다. 게이트 OFF면 처음부터 키가 없다
        assertThat(testRedis.keys(pid)).isEmpty();
    }

    @Test
    @DisplayName("같은 사용자가 다른 좌석으로 동시에 10번 잡으면 한 번만 성공한다")
    void sameUserConcurrent() {
        long pid = fixtures.newPerformance(1, 1, 10);
        int attempts = 10;
        List<Long> ids = db.seatIds(pid);
        long userId = fixtures.newUser();
        List<String> tokens = new ArrayList<>(attempts);
        for (int i = 0; i < attempts; i++) {
        }

        Results<CreateHoldResult> results = Concurrently.of(attempts).within(Duration.ofSeconds(60))
                .run(i -> service().hold(
                        new CreateHoldCommand(userId, pid, List.of(ids.get(i)))));

        assertThat(results.unexpected(ApiException.class)).isEmpty();
        assertThat(results.values()).hasSize(1);
        Map<ErrorCode, Long> rejected = ApiErrors.countByCode(results.errors(ApiException.class));
        assertThat(rejected).containsOnlyKeys(ErrorCode.USER_HAS_HOLD);
        assertThat(rejected.get(ErrorCode.USER_HAS_HOLD)).isEqualTo(attempts - 1L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM seat_hold WHERE performance_id = ? AND user_id = ?",
                Long.class, pid, userId)).isEqualTo(1);
        assertThat(db.heldSeatCount(pid)).isEqualTo(1);
        if (!gateEnabled()) {
            assertThat(testRedis.keys(pid)).isEmpty();
        }
    }

    protected SeatHoldService service() {
        return seatHoldService;
    }

    protected boolean gateEnabled() {
        return properties.gate().enabled();
    }

    // 이미 잡힌 좌석을 게이트가 거르면 SEAT_TAKEN, 게이트 없이 DB가 거르면 SEAT_STATE_CHANGED
    private ErrorCode seatConflictCode() {
        return gateEnabled() ? ErrorCode.SEAT_TAKEN : ErrorCode.SEAT_STATE_CHANGED;
    }

    private static boolean isDeadlock(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof PessimisticLockingFailureException
                    || String.valueOf(c.getMessage()).toLowerCase().contains("deadlock")) {
                return true;
            }
        }
        return false;
    }
}
