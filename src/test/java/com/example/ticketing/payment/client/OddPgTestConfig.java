package com.example.ticketing.payment.client;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// Fake PG가 내지 않는 응답(5xx, 이상한 본문)을 같은 경로 모양으로 돌려주는 시험용 PG
@TestConfiguration(proxyBeanMethods = false)
public class OddPgTestConfig {

    public static final String BASE = "/test/odd-pg/";

    @RestController
    @RequestMapping(BASE + "{mode}/payments")
    static class OddPgController {

        @PostMapping
        ResponseEntity<String> approve(@PathVariable String mode) {
            return reply(mode);
        }

        @GetMapping("/{orderId}")
        ResponseEntity<String> query(@PathVariable String mode, @PathVariable String orderId) {
            return reply(mode);
        }

        @PostMapping("/{orderId}/cancel")
        ResponseEntity<String> cancel(@PathVariable String mode, @PathVariable String orderId) {
            return reply(mode);
        }

        private static ResponseEntity<String> reply(String mode) {
            return switch (mode) {
                case "server-error" -> json(500, "{\"status\":\"APPROVED\",\"pgTxId\":\"tx\"}");
                case "not-json" -> ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body("approved!");
                case "empty" -> ResponseEntity.ok().build();
                case "unknown-status" -> json(200, "{\"status\":\"SETTLED\"}");
                case "not-found-status-on-200" -> json(200, "{\"status\":\"NOT_FOUND\"}");
                case "plain-404" -> json(404, "{\"error\":\"Not Found\"}");
                case "conflict" -> json(409, "{\"status\":\"PROCESSING\"}");
                case "bad-request" -> json(400, "{\"status\":\"DECLINED\"}");
                default -> json(200, "{\"status\":\"APPROVED\",\"pgTxId\":\"tx_" + mode + "\"}");
            };
        }

        private static ResponseEntity<String> json(int status, String body) {
            return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(body);
        }
    }
}
