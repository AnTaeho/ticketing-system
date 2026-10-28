package com.example.ticketing.support;

import java.io.UnsupportedEncodingException;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// MockMvc를 컨텍스트 하나로 만든다. @AutoConfigureMockMvc는 컨텍스트 캐시 키를 바꾸므로 쓰지 않는다
@TestComponent
@RequiredArgsConstructor
public class TestMvc {

    public static final String USER = "X-User-Id";

    private final WebApplicationContext context;
    private final JsonMapper jsonMapper;

    public MockMvc mockMvc() {
        return MockMvcBuilders.webAppContextSetup(context).build();
    }

    public JsonNode body(MvcResult result) {
        try {
            return jsonMapper.readTree(result.getResponse().getContentAsString());
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }
}
