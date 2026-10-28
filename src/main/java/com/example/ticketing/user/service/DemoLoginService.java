package com.example.ticketing.user.service;

import com.example.ticketing.common.error.ApiException;
import com.example.ticketing.common.error.ErrorCode;
import com.example.ticketing.user.domain.AppUser;
import com.example.ticketing.user.dto.DemoLoginResult;
import com.example.ticketing.user.repository.AppUserRepository;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class DemoLoginService {

    static final int MIN_NICKNAME_LENGTH = 2;
    static final int MAX_NICKNAME_LENGTH = 20;

    private final AppUserRepository appUserRepository;
    private final TransactionTemplate freshRead;

    public DemoLoginService(AppUserRepository appUserRepository, PlatformTransactionManager transactionManager) {
        this.appUserRepository = appUserRepository;
        this.freshRead = new TransactionTemplate(transactionManager);
        this.freshRead.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.freshRead.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.freshRead.setReadOnly(true);
    }

    public DemoLoginResult login(String rawNickname) {
        String nickname = normalize(rawNickname);
        AppUser user = appUserRepository.findByNickname(nickname)
                .orElseGet(() -> insertOrFind(nickname));
        return new DemoLoginResult(user.getId(), user.getNickname());
    }

    // 같은 닉네임을 동시에 넣다 유니크 위반이 나면 먼저 커밋된 행을 새 트랜잭션에서 읽는다
    private AppUser insertOrFind(String nickname) {
        try {
            return appUserRepository.saveAndFlush(AppUser.create(nickname));
        } catch (DataIntegrityViolationException e) {
            Optional<AppUser> existing = freshRead.execute(status -> appUserRepository.findByNickname(nickname));
            return existing.orElseThrow(() -> e);
        }
    }

    private static String normalize(String rawNickname) {
        String nickname = rawNickname == null ? "" : rawNickname.trim();
        int length = nickname.codePointCount(0, nickname.length());
        if (length < MIN_NICKNAME_LENGTH || length > MAX_NICKNAME_LENGTH) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    Map.of("reason", "닉네임은 %d~%d자여야 한다".formatted(MIN_NICKNAME_LENGTH, MAX_NICKNAME_LENGTH)));
        }
        return nickname;
    }
}
