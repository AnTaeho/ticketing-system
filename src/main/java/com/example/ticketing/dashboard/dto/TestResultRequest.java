package com.example.ticketing.dashboard.dto;

import com.example.ticketing.dashboard.domain.LockType;
import com.example.ticketing.dashboard.domain.LockVersion;
import com.example.ticketing.dashboard.domain.ScenarioType;
import com.example.ticketing.global.chaos.ChaosType;
import jakarta.validation.constraints.NotNull;

public record TestResultRequest(
        @NotNull LockVersion version,
        @NotNull LockType lockType,
        @NotNull ScenarioType scenarioType,
        int concurrentUsers,
        int initialStock,
        int totalRequests,
        int successCount,
        int overBookingCount,
        double tps,
        long p99ResponseMs,
        double errorRate,
        String memo,
        ChaosType chaosType,
        Integer chaosParameter,
        Integer fallbackCount,
        Integer cbTripCount
) {}
