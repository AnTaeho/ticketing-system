package com.example.ticketing.booking.payment.pg;

import com.example.ticketing.booking.BookingProperties;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
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

    static final String SCENARIO_HEADER = "X-Fake-Pg-Scenario";
    private static final Set<PgStatus> BODY_STATUSES =
            Set.of(PgStatus.APPROVED, PgStatus.DECLINED, PgStatus.CANCELLED, PgStatus.PROCESSING);
    private static final int MAX_DETAIL_BODY = 200;

    private final RestClient restClient;
    private final Environment environment;
    private final JsonMapper jsonMapper;
    private final String configuredBaseUrl;
    private volatile String resolvedBaseUrl;

    public HttpPgClient(BookingProperties properties, Environment environment, JsonMapper jsonMapper) {
        BookingProperties.Pg pg = properties.pg();
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(pg.connectTimeout())
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(pg.readTimeout());
        this.restClient = RestClient.builder().requestFactory(requestFactory).build();
        this.environment = environment;
        this.jsonMapper = jsonMapper;
        this.configuredBaseUrl = pg.baseUrl() == null ? "" : pg.baseUrl().trim();
    }

    @Override
    public PgResult approve(String orderId, int amount, Optional<PgScenario> scenario) {
        return call(Operation.APPROVE, orderId, baseUrl -> {
            RestClient.RequestBodySpec spec = restClient.post()
                    .uri(baseUrl + "/payments")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(jsonMapper.writeValueAsString(Map.of("orderId", orderId, "amount", amount)));
            scenario.ifPresent(s -> spec.header(SCENARIO_HEADER, s.name()));
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
        long started = System.nanoTime();
        PgResult result;
        try {
            String baseUrl = baseUrl();
            if (baseUrl == null) {
                result = PgResult.unknown("PG 주소를 정할 수 없다: booking.pg.base-url과 local.server.port가 모두 없다");
            } else {
                result = request.apply(baseUrl).exchangeForRequiredValue((req, res) -> map(operation,
                        res.getStatusCode().value(),
                        StreamUtils.copyToString(res.getBody(), StandardCharsets.UTF_8)));
            }
        } catch (RuntimeException e) {
            result = PgResult.unknown(describe(e));
        }
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;
        log.debug("PG {} orderId={} result={} pgTxId={} elapsedMs={} detail={}",
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

    private PgResult map(Operation operation, int httpStatus, String body) {
        if (operation == Operation.CANCEL && httpStatus == 409) {
            return PgResult.of(PgStatus.CONFLICT, null);
        }
        boolean success = httpStatus >= 200 && httpStatus < 300;
        boolean notFound = operation == Operation.QUERY && httpStatus == 404;
        if (!success && !notFound) {
            return PgResult.unknown("HTTP " + httpStatus + " " + abbreviate(body));
        }
        JsonNode json;
        try {
            json = jsonMapper.readTree(body);
        } catch (RuntimeException e) {
            return PgResult.unknown("HTTP " + httpStatus + " 본문 파싱 실패: " + abbreviate(body));
        }
        String status = json == null ? null : json.path("status").stringValue(null);
        if (notFound) {
            return PgStatus.NOT_FOUND.name().equals(status)
                    ? PgResult.of(PgStatus.NOT_FOUND, null)
                    : PgResult.unknown("HTTP 404 " + abbreviate(body));
        }
        PgStatus parsed = parseStatus(status);
        if (parsed == null || !BODY_STATUSES.contains(parsed)) {
            return PgResult.unknown("HTTP " + httpStatus + " 모르는 status: " + abbreviate(body));
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
        return body.length() <= MAX_DETAIL_BODY ? body : body.substring(0, MAX_DETAIL_BODY) + "…";
    }

    private enum Operation {
        APPROVE,
        QUERY,
        CANCEL
    }
}
