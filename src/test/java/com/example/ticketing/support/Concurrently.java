package com.example.ticketing.support;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReferenceArray;

// 동시성 테스트 러너. 모두 출발선에 세운 뒤 한꺼번에 놓고, 시간 안에 끝나는지 보고, 예외는 결과에 담아 돌려준다
public final class Concurrently {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);

    private final int tasks;
    private int threads;
    private Duration readyTimeout = DEFAULT_TIMEOUT;
    private Duration timeout = DEFAULT_TIMEOUT;

    private Concurrently(int tasks) {
        this.tasks = tasks;
        this.threads = tasks;
    }

    // 작업마다 스레드 하나씩 돌린다. threads()로 줄일 수 있다
    public static Concurrently of(int tasks) {
        return new Concurrently(tasks);
    }

    // 작업보다 스레드가 적으면 먼저 잡힌 스레드 수만큼만 출발선에서 기다린다
    public Concurrently threads(int threads) {
        this.threads = threads;
        return this;
    }

    public Concurrently readyWithin(Duration readyTimeout) {
        this.readyTimeout = readyTimeout;
        return this;
    }

    public Concurrently within(Duration timeout) {
        this.timeout = timeout;
        return this;
    }

    public <T> Results<T> run(IndexedTask<T> task) {
        try (Running<T> running = start(task)) {
            return running.await();
        }
    }

    public Results<Void> runAll(IndexedAction action) {
        return run(index -> {
            action.run(index);
            return null;
        });
    }

    // 출발시킨 뒤 바로 돌려준다. 도는 동안 테스트가 다른 일을 해야 할 때 쓴다
    public <T> Running<T> start(IndexedTask<T> task) {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(Math.min(tasks, threads));
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(tasks);
        AtomicReferenceArray<Outcome<T>> outcomes = new AtomicReferenceArray<>(tasks);
        try {
            for (int i = 0; i < tasks; i++) {
                int index = i;
                pool.submit(() -> {
                    try {
                        ready.countDown();
                        start.await();
                        outcomes.set(index, Outcome.capture(task, index));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            if (!await(ready, readyTimeout)) {
                throw new AssertionError("작업 " + tasks + "개가 " + readyTimeout + " 안에 출발선에 서지 못했다");
            }
            start.countDown();
            return new Running<>(pool, done, outcomes, timeout);
        } catch (RuntimeException | Error e) {
            pool.shutdownNow();
            throw e;
        }
    }

    private static boolean await(CountDownLatch latch, Duration timeout) {
        try {
            return latch.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    @FunctionalInterface
    public interface IndexedTask<T> {
        T call(int index) throws Exception;
    }

    @FunctionalInterface
    public interface IndexedAction {
        void run(int index) throws Exception;
    }

    public static final class Running<T> implements AutoCloseable {

        private final ExecutorService pool;
        private final CountDownLatch done;
        private final AtomicReferenceArray<Outcome<T>> outcomes;
        private final Duration timeout;

        private Running(ExecutorService pool, CountDownLatch done, AtomicReferenceArray<Outcome<T>> outcomes,
                        Duration timeout) {
            this.pool = pool;
            this.done = done;
            this.outcomes = outcomes;
            this.timeout = timeout;
        }

        // 모두 끝나기를 기다린다. 시간 안에 못 끝나면 실패다. 결과는 작업 번호 순서다
        public Results<T> await() {
            try {
                if (!Concurrently.await(done, timeout)) {
                    throw new AssertionError("동시 작업이 " + timeout + " 안에 끝나지 않았다");
                }
                List<Outcome<T>> ordered = new ArrayList<>(outcomes.length());
                for (int i = 0; i < outcomes.length(); i++) {
                    ordered.add(outcomes.get(i));
                }
                return new Results<>(ordered);
            } finally {
                pool.shutdownNow();
            }
        }

        @Override
        public void close() {
            pool.shutdownNow();
        }
    }

    public record Outcome<T>(T value, Throwable error) {

        private static <T> Outcome<T> capture(IndexedTask<T> task, int index) {
            try {
                return new Outcome<>(task.call(index), null);
            } catch (Throwable t) {
                return new Outcome<>(null, t);
            }
        }

        public boolean failed() {
            return error != null;
        }
    }

    public record Results<T>(List<Outcome<T>> outcomes) {

        // 예외 없이 끝난 작업의 값. 작업 번호 순서다
        public List<T> values() {
            return outcomes.stream().filter(o -> !o.failed()).map(Outcome::value).toList();
        }

        public List<Throwable> errors() {
            return outcomes.stream().filter(Outcome::failed).map(Outcome::error).toList();
        }

        public <E extends Throwable> List<E> errors(Class<E> type) {
            return errors().stream().filter(type::isInstance).map(type::cast).toList();
        }

        // 예상한 종류가 아닌 예외. 테스트는 이것이 비었는지 단언한다
        @SafeVarargs
        public final List<Throwable> unexpected(Class<? extends Throwable>... expected) {
            return errors().stream()
                    .filter(e -> Arrays.stream(expected).noneMatch(type -> type.isInstance(e)))
                    .toList();
        }
    }
}
