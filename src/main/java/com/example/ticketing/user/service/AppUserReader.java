package com.example.ticketing.user.service;

import com.example.ticketing.user.domain.AppUser;
import com.example.ticketing.user.repository.AppUserRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class AppUserReader {

    private final AppUserRepository appUserRepository;

    // 바깥 트랜잭션과 떨어진 새 트랜잭션에서 읽어 방금 다른 쪽이 커밋한 행을 본다
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED, readOnly = true)
    public Optional<AppUser> findCommittedByNickname(String nickname) {
        return appUserRepository.findByNickname(nickname);
    }
}
