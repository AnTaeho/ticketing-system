package com.example.ticketing.hold.dto;

import java.time.LocalDateTime;

// 선점 트랜잭션이 커밋한 hold
public record HoldPlacement(long holdId, String holdToken, LocalDateTime expiresAt) {
}
