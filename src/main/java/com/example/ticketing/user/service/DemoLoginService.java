package com.example.ticketing.user.service;

import com.example.ticketing.common.error.ApiException;
import com.example.ticketing.common.error.ErrorCode;
import com.example.ticketing.user.domain.User;
import com.example.ticketing.user.dto.DemoLoginResult;
import com.example.ticketing.user.repository.UserRepository;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DemoLoginService {

    static final int MIN_NICKNAME_LENGTH = 2;
    static final int MAX_NICKNAME_LENGTH = 20;

    private final UserRepository userRepository;

    public DemoLoginResult login(String rawNickname) {
        String nickname = normalize(rawNickname);
        User user = userRepository.findByNickname(nickname)
                .orElseGet(() -> insertOrFind(nickname));
        return DemoLoginResult.from(user);
    }

    // 같은 닉네임을 동시에 넣다 유니크 위반이 나면 먼저 커밋된 행을 다시 읽는다. 바깥 트랜잭션이 없어 새로 읽으면 보인다
    private User insertOrFind(String nickname) {
        try {
            return userRepository.saveAndFlush(User.create(nickname));
        } catch (DataIntegrityViolationException e) {
            return userRepository.findByNickname(nickname).orElseThrow(() -> e);
        }
    }

    private static String normalize(String rawNickname) {
        String nickname = rawNickname == null ? "" : rawNickname.trim();
        int length = nickname.codePointCount(0, nickname.length());
        if (length < MIN_NICKNAME_LENGTH || length > MAX_NICKNAME_LENGTH) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    Map.of("reason", "NICKNAME_LENGTH", "min", MIN_NICKNAME_LENGTH, "max", MAX_NICKNAME_LENGTH));
        }
        return nickname;
    }
}
