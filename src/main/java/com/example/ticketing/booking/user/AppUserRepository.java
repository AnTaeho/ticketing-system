package com.example.ticketing.booking.user;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    Optional<AppUser> findByNickname(String nickname);

    // 같은 사용자의 상태 변경 트랜잭션을 이 행에서 줄 세운다
    @Query(value = "SELECT id FROM app_user WHERE id = :id FOR UPDATE", nativeQuery = true)
    Optional<Long> lockById(@Param("id") long id);
}
