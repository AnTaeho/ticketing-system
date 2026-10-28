package com.example.ticketing.hold.repository;

import static java.util.function.Function.identity;
import static java.util.stream.Collectors.counting;
import static java.util.stream.Collectors.groupingBy;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.ticketing.hold.domain.GateHold;
import com.example.ticketing.hold.domain.GateResult;
import com.example.ticketing.support.Concurrently;
import com.example.ticketing.support.Concurrently.Results;
import com.example.ticketing.support.Fixtures;
import com.example.ticketing.support.IntegrationTest;
import com.example.ticketing.support.TestRedis;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@IntegrationTest
class SeatGateRedisStoreConcurrencyTest {

    private static final int THREAD_COUNT = 200;

    @Autowired
    private SeatGateRedisStore seatGateRedisStore;
    @Autowired
    private Fixtures fixtures;
    @Autowired
    private TestRedis testRedis;

    @Test
    @DisplayName("같은 2석에 200명이 동시에 잡으면 한 명만 성공하고 나머지는 SEAT_TAKEN이다")
    void sameSeatsTwoHundredUsers() {
        long pid = fixtures.redisOnlyPerformanceId();
        List<Long> seats = List.of(101L, 102L);

        Results<GateResult> results = Concurrently.of(THREAD_COUNT)
                .readyWithin(Duration.ofSeconds(30))
                .within(Duration.ofSeconds(30))
                .run(i -> seatGateRedisStore.tryHold(
                        new GateHold(i + 1, pid, "t-" + (i + 1), seats), Duration.ofMinutes(5)));

        assertThat(results.errors()).isEmpty();
        Map<GateResult, Long> counts = results.values().stream().collect(groupingBy(identity(), counting()));
        assertThat(counts.get(GateResult.ok())).isEqualTo(1);
        assertThat(counts).containsOnlyKeys(GateResult.ok(), GateResult.seatTaken(101L));
        assertThat(counts.get(GateResult.seatTaken(101L))).isEqualTo(THREAD_COUNT - 1);
        assertThat(testRedis.keys(pid)).hasSize(3);
    }
}
