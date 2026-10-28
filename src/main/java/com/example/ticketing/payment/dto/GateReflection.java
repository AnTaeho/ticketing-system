package com.example.ticketing.payment.dto;

import com.example.ticketing.hold.domain.GateHold;
import java.time.Instant;

// 커밋 뒤 게이트에 반영할 키. gateCloseAt은 확정했을 때만 있다
public record GateReflection(GateHold gate, Instant gateCloseAt) {
}
