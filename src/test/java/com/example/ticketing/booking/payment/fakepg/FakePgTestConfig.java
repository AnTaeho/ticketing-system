package com.example.ticketing.booking.payment.fakepg;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

// SLOW 시나리오를 5초 기다리지 않고 테스트가 PROCESSING 구간을 열고 닫는다
@TestConfiguration(proxyBeanMethods = false)
public class FakePgTestConfig {

    @Bean
    @Primary
    GatedSleeper gatedSleeper() {
        return new GatedSleeper();
    }

    public static class GatedSleeper implements FakePgSleeper {

        private final List<Long> calls = new CopyOnWriteArrayList<>();
        private final Semaphore entered = new Semaphore(0);
        private volatile CountDownLatch gate;

        @Override
        public void sleep(long millis) throws InterruptedException {
            calls.add(millis);
            entered.release();
            CountDownLatch current = gate;
            if (current != null) {
                current.await(30, TimeUnit.SECONDS);
            }
        }

        public void close() {
            gate = new CountDownLatch(1);
        }

        public void open() {
            CountDownLatch current = gate;
            gate = null;
            if (current != null) {
                current.countDown();
            }
        }

        public boolean awaitEntered(int count) throws InterruptedException {
            return entered.tryAcquire(count, 10, TimeUnit.SECONDS);
        }

        public List<Long> calls() {
            return List.copyOf(calls);
        }

        public void reset() {
            open();
            calls.clear();
            entered.drainPermits();
        }
    }
}
