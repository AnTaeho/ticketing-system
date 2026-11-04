package com.example.ticketing.user.dto;

import com.example.ticketing.user.domain.User;

public record DemoLoginResult(Long userId, String nickname) {

    public static DemoLoginResult from(User user) {
        return new DemoLoginResult(user.getId(), user.getNickname());
    }
}
