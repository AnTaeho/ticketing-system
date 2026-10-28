package com.example.ticketing.hold.domain;

// SCAN으로 읽은 게이트 키 하나. pttl은 밀리초이고 만료가 없으면 음수다
public record GateKeyEntry(String name, String holdToken, Long pttl) {
}
