package com.example.book_your_seat.queue.util;

import com.example.book_your_seat.common.util.JwtUtil;
import io.jsonwebtoken.Jwts;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Date;

@Slf4j
@Component
public class QueueJwtUtil extends JwtUtil {
    private final Integer expirationTime;
    QueueJwtUtil(@Value("${jwt.secret}") String secretKey, @Value("${jwt.queue_expiration_time}") Integer expirationTime) {
        super(secretKey);
        this.expirationTime = expirationTime;
    }

    public String createJwt(Long userId) {
        final Instant now = Instant.now();
        final Instant expiredAt = now.plusSeconds(expirationTime);

        return Jwts.builder()
                .claim("userId", userId.toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiredAt))
                .signWith(secretKey)
                .compact();
    }

    public Long getUserIdByToken(String token) {
        String userId = Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload()
                .get("userId", String.class);

        return Long.parseLong(userId);
    }
}
