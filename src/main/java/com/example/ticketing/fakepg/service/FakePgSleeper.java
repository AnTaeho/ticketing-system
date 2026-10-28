package com.example.ticketing.fakepg.service;

@FunctionalInterface
public interface FakePgSleeper {

    void sleep(long millis) throws InterruptedException;
}
