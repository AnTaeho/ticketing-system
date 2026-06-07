package com.example.ticketing.dashboard.dto;

import java.util.List;

public record ChartData(
        List<String> labels,
        List<Double> tps,
        List<Long> p99,
        List<Integer> overBooking,
        List<Double> errorRate
) {}
