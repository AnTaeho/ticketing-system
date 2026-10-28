package com.example.ticketing.fakepg.domain;

import com.example.ticketing.common.config.TicketingProperties;
import java.util.random.RandomGenerator;

// 시나리오가 없으면 설정 비율로 승인·거절과 지연을 정한다
public class FakePgDecider {

    private final TicketingProperties.FakePg config;
    private final RandomGenerator random;

    public FakePgDecider(TicketingProperties.FakePg config, RandomGenerator random) {
        this.config = config;
        this.random = random;
    }

    public Plan plan(FakePgScenario scenario) {
        if (scenario != null) {
            return switch (scenario) {
                case SUCCESS -> new Plan(true, 0);
                case DECLINE -> new Plan(false, 0);
                case SLOW_SUCCESS -> new Plan(true, config.slowDelayMs());
                case SLOW_DECLINE -> new Plan(false, config.slowDelayMs());
            };
        }
        boolean approve = random.nextDouble() >= config.declineRate();
        boolean slow = random.nextDouble() < config.slowRate();
        return new Plan(approve, slow ? config.slowDelayMs() : normalDelayMs());
    }

    private long normalDelayMs() {
        long min = config.delayMinMs();
        long max = config.delayMaxMs();
        return max > min ? random.nextLong(min, max + 1) : min;
    }

    public record Plan(boolean approve, long delayMs) {
    }
}
