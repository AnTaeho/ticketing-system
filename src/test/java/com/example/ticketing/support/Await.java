package com.example.ticketing.support;

import java.time.Duration;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

// 비동기로 끝나는 일을 짧은 간격으로 다시 확인하며 기다린다
public final class Await {

    private Await() {
    }

    // 시간 안에 조건이 참이 되면 true
    public static boolean until(Duration timeout, Duration interval, BooleanSupplier condition) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) {
                return false;
            }
            sleep(interval);
        }
        return true;
    }

    // 값이 조건을 만족하거나 시간이 다 될 때까지 다시 읽고, 마지막으로 읽은 값을 돌려준다
    public static <T> T until(Duration timeout, Duration interval, Supplier<T> read, Predicate<? super T> done) {
        long deadline = System.nanoTime() + timeout.toNanos();
        T last = read.get();
        while (!done.test(last) && System.nanoTime() < deadline) {
            sleep(interval);
            last = read.get();
        }
        return last;
    }

    private static void sleep(Duration interval) {
        try {
            Thread.sleep(interval.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
