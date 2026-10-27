package com.example.ticketing.booking.payment;

import com.example.ticketing.booking.payment.pg.PgClient;
import com.example.ticketing.booking.payment.pg.PgResult;
import com.example.ticketing.booking.payment.pg.PgScenario;
import com.example.ticketing.booking.payment.pg.PgStatus;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

// HTTP 없이 PG 결과를 정하는 대역. 컨텍스트가 공유되므로 테스트마다 reset()한다
public class StubPgClient implements PgClient {

    private final AtomicInteger approveCalls = new AtomicInteger();
    private final AtomicInteger queryCalls = new AtomicInteger();
    private final AtomicInteger cancelCalls = new AtomicInteger();
    private final List<ApproveCall> approvals = new CopyOnWriteArrayList<>();
    private final Map<String, PgResult> approveByOrder = new ConcurrentHashMap<>();
    private final Map<String, PgResult> queryByOrder = new ConcurrentHashMap<>();
    private final Map<String, PgResult> cancelByOrder = new ConcurrentHashMap<>();
    private volatile PgResult approveResult;
    private volatile PgResult queryResult;
    private volatile PgResult cancelResult;
    private volatile long approveDelayMs;
    private volatile Consumer<String> beforeApproveReturns;

    public StubPgClient() {
        reset();
    }

    public final void reset() {
        approveCalls.set(0);
        queryCalls.set(0);
        cancelCalls.set(0);
        approvals.clear();
        approveByOrder.clear();
        queryByOrder.clear();
        cancelByOrder.clear();
        approveResult = null;
        queryResult = PgResult.of(PgStatus.NOT_FOUND, null);
        cancelResult = PgResult.of(PgStatus.CANCELLED, null);
        approveDelayMs = 0;
        beforeApproveReturns = orderId -> {
        };
    }

    @Override
    public PgResult approve(String orderId, int amount, Optional<PgScenario> scenario) {
        approveCalls.incrementAndGet();
        approvals.add(new ApproveCall(orderId, amount, scenario));
        sleep(approveDelayMs);
        beforeApproveReturns.accept(orderId);
        PgResult result = approveByOrder.getOrDefault(orderId, approveResult);
        return result != null ? result : approved(orderId);
    }

    @Override
    public PgResult query(String orderId) {
        queryCalls.incrementAndGet();
        return queryByOrder.getOrDefault(orderId, queryResult);
    }

    @Override
    public PgResult cancel(String orderId) {
        cancelCalls.incrementAndGet();
        return cancelByOrder.getOrDefault(orderId, cancelResult);
    }

    // 승인 결과를 정하지 않으면 APPROVED + stub-tx-<orderId>
    public static PgResult approved(String orderId) {
        return PgResult.of(PgStatus.APPROVED, "stub-tx-" + orderId);
    }

    public StubPgClient approveWith(PgResult result) {
        this.approveResult = result;
        return this;
    }

    public StubPgClient approveWith(String orderId, PgResult result) {
        approveByOrder.put(orderId, result);
        return this;
    }

    public StubPgClient queryWith(PgResult result) {
        this.queryResult = result;
        return this;
    }

    public StubPgClient queryWith(String orderId, PgResult result) {
        queryByOrder.put(orderId, result);
        return this;
    }

    public StubPgClient cancelWith(PgResult result) {
        this.cancelResult = result;
        return this;
    }

    public StubPgClient cancelWith(String orderId, PgResult result) {
        cancelByOrder.put(orderId, result);
        return this;
    }

    public StubPgClient approveDelayMs(long delayMs) {
        this.approveDelayMs = delayMs;
        return this;
    }

    // 승인 결과를 돌려주기 직전에 실행한다. PG 호출 동안 일어난 일을 재현할 때 쓴다
    public StubPgClient beforeApproveReturns(Consumer<String> hook) {
        this.beforeApproveReturns = hook;
        return this;
    }

    public int approveCalls() {
        return approveCalls.get();
    }

    public int queryCalls() {
        return queryCalls.get();
    }

    public int cancelCalls() {
        return cancelCalls.get();
    }

    public List<ApproveCall> approvals() {
        return List.copyOf(approvals);
    }

    private static void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    public record ApproveCall(String orderId, int amount, Optional<PgScenario> scenario) {
    }
}
