package com.example.ticketing.fakepg.controller;

import com.example.ticketing.common.web.HttpNames;
import com.example.ticketing.fakepg.domain.FakePgScenario;
import com.example.ticketing.fakepg.domain.FakePgStatus;
import com.example.ticketing.fakepg.dto.FakePgApproveRequest;
import com.example.ticketing.fakepg.dto.FakePgReply;
import com.example.ticketing.fakepg.service.FakePgService;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 검증 실패는 예외를 던지지 않고 PG 형식으로 직접 답한다. 본문을 읽지 못한 요청만 ApiExceptionHandler가 예매 형식 400으로 답한다
@Profile("!prod")
@RestController
@RequestMapping("/fake-pg/payments")
@RequiredArgsConstructor
public class FakePgController {

    private static final int MAX_ORDER_ID_LENGTH = 40;

    private final FakePgService fakePgService;

    @PostMapping
    public ResponseEntity<FakePgReply> approve(@RequestBody FakePgApproveRequest request,
                                               @RequestHeader(value = HttpNames.PG_SCENARIO_HEADER, required = false)
                                               String scenarioHeader) {
        String orderId = request.orderId();
        if (!validOrderId(orderId)) {
            return badRequest(orderId, "orderId는 1~" + MAX_ORDER_ID_LENGTH + "자여야 한다");
        }
        if (request.amount() == null || request.amount() < 0) {
            return badRequest(orderId, "amount는 0 이상이어야 한다");
        }
        FakePgScenario scenario = null;
        if (scenarioHeader != null && !scenarioHeader.isBlank()) {
            Optional<FakePgScenario> parsed = FakePgScenario.parse(scenarioHeader);
            if (parsed.isEmpty()) {
                return badRequest(orderId, "알 수 없는 시나리오: " + scenarioHeader);
            }
            scenario = parsed.get();
        }
        return ResponseEntity.ok(fakePgService.approve(orderId, request.amount(), scenario));
    }

    @GetMapping("/{orderId}")
    public ResponseEntity<FakePgReply> query(@PathVariable String orderId) {
        FakePgReply reply = fakePgService.query(orderId);
        return ResponseEntity.status(reply.isNotFound() ? HttpStatus.NOT_FOUND : HttpStatus.OK).body(reply);
    }

    @PostMapping("/{orderId}/cancel")
    public ResponseEntity<FakePgReply> cancel(@PathVariable String orderId) {
        if (!validOrderId(orderId)) {
            return badRequest(orderId, "orderId는 1~" + MAX_ORDER_ID_LENGTH + "자여야 한다");
        }
        FakePgReply reply = fakePgService.cancel(orderId);
        return ResponseEntity.status(reply.is(FakePgStatus.PROCESSING) ? HttpStatus.CONFLICT : HttpStatus.OK)
                .body(reply);
    }

    private static boolean validOrderId(String orderId) {
        return orderId != null && !orderId.isBlank() && orderId.length() <= MAX_ORDER_ID_LENGTH;
    }

    private static ResponseEntity<FakePgReply> badRequest(String orderId, String reason) {
        return ResponseEntity.badRequest().body(FakePgReply.invalid(orderId, reason));
    }
}
