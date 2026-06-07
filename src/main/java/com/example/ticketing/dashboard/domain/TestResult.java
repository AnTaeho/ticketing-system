package com.example.ticketing.dashboard.domain;

import com.example.ticketing.global.chaos.ChaosType;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Entity
@Getter
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TestResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    private LockVersion version;

    @Enumerated(EnumType.STRING)
    private LockType lockType;

    @Enumerated(EnumType.STRING)
    private ScenarioType scenarioType;

    private int concurrentUsers;
    private int initialStock;
    private int totalRequests;
    private int successCount;
    private int overBookingCount;
    private double tps;
    private long p99ResponseMs;
    private double errorRate;
    private String memo;

    @Enumerated(EnumType.STRING)
    private ChaosType chaosType;

    private Integer chaosParameter;

    @CreatedDate
    private LocalDateTime testedAt;

    public static TestResult of(LockVersion version, LockType lockType, ScenarioType scenarioType,
                                int concurrentUsers, int initialStock, int totalRequests,
                                int successCount, int overBookingCount, double tps,
                                long p99ResponseMs, double errorRate, String memo,
                                ChaosType chaosType, Integer chaosParameter) {
        TestResult r = new TestResult();
        r.version = version;
        r.lockType = lockType;
        r.scenarioType = scenarioType;
        r.concurrentUsers = concurrentUsers;
        r.initialStock = initialStock;
        r.totalRequests = totalRequests;
        r.successCount = successCount;
        r.overBookingCount = overBookingCount;
        r.tps = tps;
        r.p99ResponseMs = p99ResponseMs;
        r.errorRate = errorRate;
        r.memo = memo;
        r.chaosType = chaosType != null ? chaosType : ChaosType.NONE;
        r.chaosParameter = chaosParameter;
        return r;
    }
}
