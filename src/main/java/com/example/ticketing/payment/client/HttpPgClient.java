package com.example.ticketing.payment.client;

import com.example.ticketing.common.config.TicketingProperties;
import com.example.ticketing.common.text.Texts;
import com.example.ticketing.common.web.HttpNames;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.LongSupplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// 본문의 status만 믿는다. 타임아웃·연결 실패·예상 밖 응답은 전부 UNKNOWN으로 돌려 좌석을 풀지 않게 한다
@Slf4j
@Component
public class HttpPgClient implements PgClient {

    private static final Set<PgStatus> BODY_STATUSES =
            Set.of(PgStatus.APPROVED, PgStatus.DECLINED, PgStatus.CANCELLED, PgStatus.PROCESSING);
    private static final int MAX_DETAIL_BODY = 200;

    private final RestClient restClient;
    private final Environment environment;
    private final JsonMapper jsonMapper;
    private final LongSupplier nanoClock;
    private final String configuredBaseUrl;
    private volatile String resolvedBaseUrl;

    public HttpPgClient(TicketingProperties properties, Environment environment, JsonMapper jsonMapper,
                        LongSupplier nanoClock) {
        TicketingProperties.Pg pg = properties.pg();
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(pg.connectTimeout())
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(pg.readTimeout());
        this.restClient = RestClient.builder().requestFactory(requestFactory).build();
        this.environment = environment;
        this.jsonMapper = jsonMapper;
        this.nanoClock = nanoClock;
        this.configuredBaseUrl = pg.baseUrl() == null ? "" : pg.baseUrl().trim();
    }

    @Override
    public PgResult approve(String orderId, int amount, Optional<String> scenario) {
        return call(Operation.APPROVE, orderId, baseUrl -> {
            RestClient.RequestBodySpec spec = restClient.post()
                    .uri(baseUrl + "/payments")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(jsonMapper.writeValueAsString(Map.of("orderId", orderId, "amount", amount)));
            scenario.ifPresent(s -> spec.header(HttpNames.PG_SCENARIO_HEADER, s));
            return spec;
        });
    }

    @Override
    public PgResult query(String orderId) {
        return call(Operation.QUERY, orderId, baseUrl -> restClient.get()
                .uri(baseUrl + "/payments/{orderId}", orderId)
                .accept(MediaType.APPLICATION_JSON));
    }

    @Override
    public PgResult cancel(String orderId) {
        return call(Operation.CANCEL, orderId, baseUrl -> restClient.post()
                .uri(baseUrl + "/payments/{orderId}/cancel", orderId)
                .accept(MediaType.APPLICATION_JSON));
    }

    private PgResult call(Operation operation, String orderId,
                          Function<String, RestClient.RequestHeadersSpec<?>> request) {
        long started = nanoClock.getAsLong();
        PgResult result;
        try {
            String baseUrl = baseUrl();
            if (baseUrl == null) {
                result = PgResult.unknown("PG 주소를 정할 수 없다: ticketing.pg.base-url과 local.server.port가 모두 없다");
            } else {
                result = request.apply(baseUrl).exchangeForRequiredValue((req, res) -> map(operation,
                        res.getStatusCode(), StreamUtils.copyToString(res.getBody(), StandardCharsets.UTF_8)));
            }
        } catch (RuntimeException e) {
            result = PgResult.unknown(describe(e));
        }
        long elapsedMs = (nanoClock.getAsLong() - started) / 1_000_000;
        log.debug("[pg] 호출 결과 op={} orderId={} result={} pgTxId={} elapsedMs={} detail={}",
                operation, orderId, result.status(), result.pgTxId(), elapsedMs, result.detail());
        return result;
    }

    // 서버 포트는 기동이 끝나야 정해지므로 첫 호출 때 읽는다
    private String baseUrl() {
        if (!configuredBaseUrl.isEmpty()) {
            return configuredBaseUrl;
        }
        String resolved = resolvedBaseUrl;
        if (resolved == null) {
            String port = environment.getProperty("local.server.port");
            if (port == null || port.isBlank()) {
                return null;
            }
            resolved = "http://localhost:" + port.trim() + "/fake-pg";
            resolvedBaseUrl = resolved;
        }
        return resolved;
    }

    private PgResult map(Operation operation, HttpStatusCode httpStatus, String body) {
        if (operation == Operation.CANCEL && httpStatus.isSameCodeAs(HttpStatus.CONFLICT)) {
            return PgResult.of(PgStatus.CONFLICT, null);
        }
        boolean notFound = operation == Operation.QUERY && httpStatus.isSameCodeAs(HttpStatus.NOT_FOUND);
        int code = httpStatus.value();
        if (!httpStatus.is2xxSuccessful() && !notFound) {
            return PgResult.unknown("HTTP " + code + " " + abbreviate(body));
        }
        JsonNode json;
        try {
            json = jsonMapper.readTree(body);
        } catch (RuntimeException e) {
            return PgResult.unknown("HTTP " + code + " 본문 파싱 실패: " + abbreviate(body));
        }
        String status = json == null ? null : json.path("status").stringValue(null);
        if (notFound) {
            return PgStatus.NOT_FOUND.name().equals(status)
                    ? PgResult.of(PgStatus.NOT_FOUND, null)
                    : PgResult.unknown("HTTP " + code + " " + abbreviate(body));
        }
        PgStatus parsed = parseStatus(status);
        if (parsed == null || !BODY_STATUSES.contains(parsed)) {
            return PgResult.unknown("HTTP " + code + " 모르는 status: " + abbreviate(body));
        }
        return PgResult.of(parsed, json.path("pgTxId").stringValue(null));
    }

    private static PgStatus parseStatus(String status) {
        if (status == null) {
            return null;
        }
        for (PgStatus candidate : PgStatus.values()) {
            if (candidate.name().equals(status)) {
                return candidate;
            }
        }
        return null;
    }

    private static String describe(Throwable e) {
        StringBuilder detail = new StringBuilder(e.getClass().getSimpleName()).append(": ").append(e.getMessage());
        Throwable cause = e.getCause();
        if (cause != null && cause != e) {
            detail.append(" (").append(cause.getClass().getSimpleName()).append(")");
        }
        return detail.toString();
    }

    private static String abbreviate(String body) {
        if (body == null || body.isEmpty()) {
            return "(본문 없음)";
        }
        return Texts.abbreviate(body, MAX_DETAIL_BODY);
    }

    private enum Operation {
        APPROVE,
        QUERY,
        CANCEL
    }
}
