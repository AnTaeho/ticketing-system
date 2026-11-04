package com.example.ticketing.user.repository;

import com.example.ticketing.user.domain.User;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByNickname(String nickname);

    // 같은 사용자의 상태 변경 트랜잭션을 이 행에서 줄 세운다
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u.id from User u where u.id = :id")
    Optional<Long> lockById(@Param("id") long id);
}
