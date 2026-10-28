package com.example.ticketing.hold.domain;

import com.example.ticketing.common.redis.RedisKeys;
import java.util.List;

// seatId는 SEAT_TAKEN·COOLDOWN일 때만 있다
public record GateResult(Type type, Long seatId) {

    public enum Type {
        OK, USER_HAS_HOLD, SEAT_TAKEN, COOLDOWN
    }

    private static final GateResult OK = new GateResult(Type.OK, null);
    private static final GateResult USER_HAS_HOLD = new GateResult(Type.USER_HAS_HOLD, null);

    public static GateResult ok() {
        return OK;
    }

    public static GateResult userHasHold() {
        return USER_HAS_HOLD;
    }

    public static GateResult seatTaken(long seatId) {
        return new GateResult(Type.SEAT_TAKEN, seatId);
    }

    public static GateResult cooldown(long seatId) {
        return new GateResult(Type.COOLDOWN, seatId);
    }

    public static GateResult fromScript(List<?> reply) {
        if (reply == null || reply.isEmpty()) {
            throw new IllegalStateException("hold.lua가 빈 결과를 돌려줬다");
        }
        Type type = Type.valueOf(String.valueOf(reply.get(0)));
        return switch (type) {
            case OK -> OK;
            case USER_HAS_HOLD -> USER_HAS_HOLD;
            case SEAT_TAKEN -> seatTaken(RedisKeys.seatIdOf(String.valueOf(reply.get(1))));
            case COOLDOWN -> cooldown(RedisKeys.seatIdOf(String.valueOf(reply.get(1))));
        };
    }
}
