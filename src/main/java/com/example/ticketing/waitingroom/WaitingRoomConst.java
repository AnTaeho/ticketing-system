package com.example.ticketing.waitingroom;

public final class WaitingRoomConst {

    public static final int PROCESSING_QUEUE_SIZE = 200;
    public static final long PROCESSING_TOKEN_TTL_MS = 30 * 60 * 1000L;
    public static final long WAITING_TOKEN_TTL_MS = 60 * 60 * 1000L;

    public static final String PROCESSING_KEY_PREFIX = "queue:processing:";
    public static final String WAITING_KEY_PREFIX = "queue:waiting:";

    public static final String STATUS_PROCESSING = "PROCESSING";
    public static final String STATUS_WAITING = "WAITING";
    public static final String STATUS_NOT_IN_QUEUE = "NOT_IN_QUEUE";

    private WaitingRoomConst() {}
}
