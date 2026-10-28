package com.example.ticketing.user.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ticketing.support.IntegrationTest;
import com.example.ticketing.support.TestMvc;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@IntegrationTest
class DemoLoginApiControllerTest {

    @Autowired
    private TestMvc testMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = testMvc.mockMvc();
    }

    @Test
    @DisplayName("로그인하면 userId·nickname과 bk_uid 쿠키를 돌려준다")
    void loginSetsCookie() throws Exception {
        String nickname = "api-" + UUID.randomUUID().toString().substring(0, 8);

        MvcResult result = mockMvc.perform(post("/api/demo/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\" %s \"}".formatted(nickname)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").isNumber())
                .andExpect(jsonPath("$.nickname").value(nickname))
                .andReturn();

        Long userId = jdbcTemplate.queryForObject("SELECT id FROM app_user WHERE nickname = ?", Long.class, nickname);
        String setCookie = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertThat(setCookie)
                .startsWith("bk_uid=" + userId + ";")
                .contains("Path=/")
                .contains("SameSite=Lax")
                .contains("HttpOnly");
    }

    @Test
    @DisplayName("닉네임이 2자 미만이거나 20자를 넘으면 쿠키 없이 400으로 거절한다")
    void rejectsInvalidNickname() throws Exception {
        for (String body : new String[]{"{\"nickname\":\"가\"}", "{\"nickname\":\"%s\"}".formatted("가".repeat(21)),
                "{\"nickname\":\"   \"}", "{}"}) {
            MvcResult result = mockMvc.perform(post("/api/demo/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                    .andReturn();
            assertThat(result.getResponse().getHeader(HttpHeaders.SET_COOKIE)).isNull();
        }
    }
}
