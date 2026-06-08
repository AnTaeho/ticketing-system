package com.example.book_your_seat.queue;

public final class QueueConst {
    public static final String PROCESSING = "PROCESSING";
    public static final String WAITING = "WAITING";
    public static final String NOT_IN_QUEUE = "NOT_IN_QUEUE";

    public static final Integer ZERO = 0;
    public static final Integer FIVE = 5;
    public static final Integer MINUTE = 60;
    public static final Integer PROCESSING_QUEUE_SIZE = 1000;
    public static final Integer PROCESSING_TOKEN_EXPIRATION_TIME = 30 * 60 * 1000; //30분
    public static final Integer WAITING_TOKEN_EXPIRATION_TIME = 60 * 60 * 1000; //1시간

    public static final String PROCESSING_QUEUE_KEY = "processing_queue_key";
    public static final String WAITING_QUEUE_KEY = "waiting_queue_key";
    public static final String ALREADY_ISSUED_USER = "이미 토큰을 발급받은 유저입니다.";
    public static final String REMOVE_BAD_REQUEST = "로그인한 유저의 토큰이 아닙니다.";

    public static final String NOT_IN_PROCESSING_QUEUE = "처리 가능한 유저가 아닙니다.";

}
