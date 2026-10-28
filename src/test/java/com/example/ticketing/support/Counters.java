package com.example.ticketing.support;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.test.context.TestComponent;

// 카운터 읽기. 테스트는 앞뒤 값의 차이로 단언한다. 컨텍스트 없는 테스트는 new로 만든다
@TestComponent
public final class Counters {

    private final MeterRegistry registry;

    public Counters(MeterRegistry registry) {
        this.registry = registry;
    }

    public double count(String name, String... tags) {
        return registry.counter(name, tags).count();
    }

    // 태그와 상관없이 이름이 같은 카운터를 모두 더한다
    public double sum(String name) {
        return registry.find(name).counters().stream().mapToDouble(Counter::count).sum();
    }
}
