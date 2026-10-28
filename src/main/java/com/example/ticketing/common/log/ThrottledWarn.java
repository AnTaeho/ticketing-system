package com.example.ticketing.common.log;

import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.slf4j.Logger;

// 장애 중 같은 경고가 요청·주기마다 쌓이지 않게 한 주기에 한 번만 warn으로 남기고, 그사이는 debug로 세어 다음 warn에 붙인다
public final class ThrottledWarn {

    private static final Duration INTERVAL = Duration.ofSeconds(10);

    private final Logger log;
    private final long intervalNanos;
    private final LongSupplier nanoClock;
    private final AtomicLong lastWarnNanos;
    private final AtomicLong suppressed = new AtomicLong();

    public ThrottledWarn(Logger log, LongSupplier nanoClock) {
        this.log = log;
        this.intervalNanos = INTERVAL.toNanos();
        this.nanoClock = nanoClock;
        this.lastWarnNanos = new AtomicLong(nanoClock.getAsLong() - intervalNanos);
    }

    public void warn(String format, Object... args) {
        long now = nanoClock.getAsLong();
        long last = lastWarnNanos.get();
        if (now - last >= intervalNanos && lastWarnNanos.compareAndSet(last, now)) {
            Object[] withCount = Arrays.copyOf(args, args.length + 1);
            withCount[args.length] = suppressed.getAndSet(0);
            log.warn(format + " (앞서 생략 {}건)", withCount);
        } else {
            suppressed.incrementAndGet();
            log.debug(format, args);
        }
    }
}
