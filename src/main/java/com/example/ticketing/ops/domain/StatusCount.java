package com.example.ticketing.ops.domain;

// status는 그 테이블의 상태 enum이다
public record StatusCount(Enum<?> status, long count) {
}
