package com.example.ticketing.fakepg.domain;

import com.example.ticketing.common.config.TicketingProperties;
import java.util.random.RandomGenerator;

// 시나리오가 없으면 설정 비율로 승인·거절과 지연을 정한다
public class FakePgDecider {

    private final TicketingProperties.FakePg config;
    private final RandomGenerator random;
    private final long delayMinMs;
    private final long delayMaxMs;
    private final long slowDelayMs;

    public FakePgDecider(TicketingProperties.FakePg config, RandomGenerator random) {
        this.config = config;
        this.random = random;
        this.delayMinMs = config.delayMin().toMillis();
        this.delayMaxMs = config.delayMax().toMillis();
        this.slowDelayMs = config.slowDelay().toMillis();
    }

    public Plan plan(FakePgScenario scenario) {
        if (scenario != null) {
            return switch (scenario) {
                case SUCCESS -> new Plan(true, 0);
                case DECLINE -> new Plan(false, 0);
                case SLOW_SUCCESS -> new Plan(true, slowDelayMs);
                case SLOW_DECLINE -> new Plan(false, slowDelayMs);
            };
        }
        boolean approve = random.nextDouble() >= config.declineRate();
        boolean slow = random.nextDouble() < config.slowRate();
        return new Plan(approve, slow ? slowDelayMs : normalDelayMs());
    }

    private long normalDelayMs() {
        return delayMaxMs > delayMinMs ? random.nextLong(delayMinMs, delayMaxMs + 1) : delayMinMs;
    }

    public record Plan(boolean approve, long delayMs) {
    }
}
