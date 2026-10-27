package com.example.ticketing.booking.payment.fakepg;

@FunctionalInterface
public interface FakePgSleeper {

    void sleep(long millis) throws InterruptedException;
}
