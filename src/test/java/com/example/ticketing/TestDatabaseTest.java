package com.example.ticketing;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ticketing.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

@IntegrationTest
class TestDatabaseTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private StringRedisTemplate redis;

    @Test
    @DisplayName("예매 테스트는 앱 DB와 다른, 이름이 _test로 끝나는 전용 DB에 붙는다")
    void usesDedicatedDatabase() {
        assertThat(jdbcTemplate.queryForObject("SELECT DATABASE()", String.class))
                .isNotEqualTo("ticketing").endsWith("_test");
    }

    @Test
    @DisplayName("예매 테스트의 Redis 연결은 앱이 쓰는 DB 0이 아닌 번호를 쓴다")
    void usesSeparateRedisDatabase() {
        LettuceConnectionFactory factory = (LettuceConnectionFactory) redis.getConnectionFactory();

        assertThat(factory.getDatabase()).isNotZero();
    }
}
