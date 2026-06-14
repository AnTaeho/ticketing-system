package com.example.ticketing.demo;

public record DemoStats(
        int totalUsers,
        int enqueuedCount,
        int successCount,
        int soldOutCount,
        int failedCount,
        int abandonedCount,
        int processingCount,
        int waitingCount,
        long redisStock,
        boolean running
) {}
