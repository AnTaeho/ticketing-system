package com.example.ticketing.hold.domain;

import java.util.Set;

public enum HoldStatus {
    HELD,
    PAYING,
    CONFIRMED,
    EXPIRED,
    RELEASED;

    // 회차마다 한 사람이 하나만 가질 수 있는 진행 중 선점
    public static final Set<HoldStatus> ACTIVE = Set.of(HELD, PAYING);
}
