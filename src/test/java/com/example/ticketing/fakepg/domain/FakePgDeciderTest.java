package com.example.ticketing.fakepg.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.example.ticketing.common.config.TicketingProperties;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Random;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FakePgDeciderTest {

    private static final TicketingProperties.FakePg CONFIG = new TicketingProperties.FakePg(
            Duration.ofMillis(200), Duration.ofMillis(1200), 0.1, 0.05, Duration.ofSeconds(5), Duration.ofSeconds(30));

    @Test
    @DisplayName("시나리오를 주면 느린 시나리오만 정해진 지연 뒤에 결론낸다")
    void explicitScenarios() {
        FakePgDecider decider = new FakePgDecider(CONFIG, new Random(1));

        assertThat(decider.plan(FakePgScenario.SUCCESS)).isEqualTo(new FakePgDecider.Plan(true, 0));
        assertThat(decider.plan(FakePgScenario.DECLINE)).isEqualTo(new FakePgDecider.Plan(false, 0));
        assertThat(decider.plan(FakePgScenario.SLOW_SUCCESS)).isEqualTo(new FakePgDecider.Plan(true, 5000));
        assertThat(decider.plan(FakePgScenario.SLOW_DECLINE)).isEqualTo(new FakePgDecider.Plan(false, 5000));
    }

    @Test
    @DisplayName("시나리오가 없으면 난수가 decline-rate·slow-rate 경계 아래일 때만 거절·느린 응답이 된다")
    void boundaries() {
        // 순서: 거절 판정, 느림 판정
        FakePgDecider decider = new FakePgDecider(CONFIG, new ScriptedRandom(
                0.0999, 0.0499,
                0.1, 0.05,
                0.5, 0.9));

        assertThat(decider.plan(null)).isEqualTo(new FakePgDecider.Plan(false, 5000));
        FakePgDecider.Plan edge = decider.plan(null);
        assertThat(edge.approve()).isTrue();
        assertThat(edge.delayMs()).isEqualTo(200);
        assertThat(decider.plan(null)).isEqualTo(new FakePgDecider.Plan(true, 200));
    }

    @Test
    @DisplayName("시나리오가 없으면 거절·느린 응답 비율과 지연 범위가 설정을 따른다")
    void ratiosFollowConfig() {
        FakePgDecider decider = new FakePgDecider(CONFIG, new Random(20261027L));
        int draws = 200_000;
        int declined = 0;
        int slow = 0;
        long minDelay = Long.MAX_VALUE;
        long maxDelay = Long.MIN_VALUE;
        for (int i = 0; i < draws; i++) {
            FakePgDecider.Plan plan = decider.plan(null);
            if (!plan.approve()) {
                declined++;
            }
            if (plan.delayMs() == CONFIG.slowDelay().toMillis()) {
                slow++;
            } else {
                minDelay = Math.min(minDelay, plan.delayMs());
                maxDelay = Math.max(maxDelay, plan.delayMs());
            }
        }

        assertThat((double) declined / draws).isCloseTo(0.1, within(0.005));
        assertThat((double) slow / draws).isCloseTo(0.05, within(0.004));
        assertThat(minDelay).isBetween(200L, 210L);
        assertThat(maxDelay).isBetween(1190L, 1200L);
    }

    // nextDouble은 넘겨준 값을 차례로, 범위 nextLong은 하한을 돌려준다
    private static final class ScriptedRandom implements RandomGenerator {

        private final Deque<Double> doubles;

        ScriptedRandom(Double... values) {
            this.doubles = new ArrayDeque<>(List.of(values));
        }

        @Override
        public double nextDouble() {
            return doubles.removeFirst();
        }

        @Override
        public long nextLong(long origin, long bound) {
            return origin;
        }

        @Override
        public long nextLong() {
            throw new UnsupportedOperationException();
        }
    }
}
