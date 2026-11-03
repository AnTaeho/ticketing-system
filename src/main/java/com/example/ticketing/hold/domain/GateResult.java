package com.example.ticketing.hold.domain;

import com.example.ticketing.common.redis.RedisKeys;
import java.util.List;

// seatId는 SEAT_TAKEN·COOLDOWN일 때만 있다
public record GateResult(Type type, Long seatId) {

    public enum Type {
        OK, USER_HAS_HOLD, SEAT_TAKEN, COOLDOWN
    }

    public static GateResult ok() {
        return new GateResult(Type.OK, null);
    }

    public static GateResult seatTaken(long seatId) {
        return new GateResult(Type.SEAT_TAKEN, seatId);
    }

    public static GateResult cooldown(long seatId) {
        return new GateResult(Type.COOLDOWN, seatId);
    }

    // hold.lua 반환은 {종류} 또는 {종류, 좌석 키·쿨다운 키}다
    public static GateResult fromScript(List<?> reply) {
        Long seatId = reply.size() > 1 ? RedisKeys.seatIdOf(String.valueOf(reply.get(1))) : null;
        return new GateResult(Type.valueOf(String.valueOf(reply.get(0))), seatId);
    }
}
