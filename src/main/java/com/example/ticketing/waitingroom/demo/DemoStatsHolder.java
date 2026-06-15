package com.example.ticketing.waitingroom.demo;

import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

@Component
public class DemoStatsHolder {

    private final AtomicInteger totalUsers     = new AtomicInteger(0);
    private final AtomicInteger enqueuedCount  = new AtomicInteger(0);
    private final AtomicInteger successCount   = new AtomicInteger(0);
    private final AtomicInteger soldOutCount   = new AtomicInteger(0);
    private final AtomicInteger failedCount    = new AtomicInteger(0);
    private final AtomicInteger abandonedCount = new AtomicInteger(0);
    private volatile int initialStock = 0;
    private volatile boolean running = false;

    public void reset(int total, int initialStock) {
        totalUsers.set(total);
        enqueuedCount.set(0);
        successCount.set(0);
        soldOutCount.set(0);
        failedCount.set(0);
        abandonedCount.set(0);
        this.initialStock = initialStock;
        running = true;
    }

    public void incrementEnqueued()  { enqueuedCount.incrementAndGet(); }
    public void incrementSuccess()   { successCount.incrementAndGet();  checkDone(); }
    public void incrementSoldOut()   { soldOutCount.incrementAndGet();  checkDone(); }
    public void incrementFailed()    { failedCount.incrementAndGet();   checkDone(); }
    public void incrementAbandoned() { abandonedCount.incrementAndGet(); checkDone(); }

    public boolean isRunning() { return running; }

    public DemoStats snapshot(int processingCount, int waitingCount, long redisStock) {
        return new DemoStats(
                totalUsers.get(),
                enqueuedCount.get(),
                successCount.get(),
                soldOutCount.get(),
                failedCount.get(),
                abandonedCount.get(),
                processingCount,
                waitingCount,
                redisStock,
                initialStock,
                running
        );
    }

    private void checkDone() {
        int done = successCount.get() + soldOutCount.get() + failedCount.get() + abandonedCount.get();
        int total = totalUsers.get();
        if (total > 0 && done >= total) running = false;
    }
}
