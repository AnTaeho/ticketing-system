package com.example.ticketing.ops.service;

import com.example.ticketing.common.metrics.Metrics;
import com.example.ticketing.common.metrics.Metrics.Tag;
import com.example.ticketing.ops.dto.OpsSummary.CounterView;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

// 서버가 켜진 뒤 모든 회차를 합친 카운터 값을 태그별로 모은다
@Component
public class OpsMetricsReader {

    // 태그 순서는 값을 이어 붙이는 순서다
    private static final Map<String, List<String>> COUNTERS = counters();

    private final MeterRegistry meterRegistry;

    public OpsMetricsReader(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public Map<String, CounterView> counterValues() {
        Map<String, CounterView> result = new LinkedHashMap<>();
        COUNTERS.forEach((name, tags) -> {
            Map<String, Long> values = new TreeMap<>();
            for (Counter counter : meterRegistry.find(name).counters()) {
                String key = tags.isEmpty() ? "total" : String.join("/",
                        tags.stream().map(tag -> String.valueOf(counter.getId().getTag(tag))).toList());
                values.merge(key, Math.round(counter.count()), Long::sum);
            }
            long total = values.values().stream().mapToLong(Long::longValue).sum();
            result.put(name, new CounterView(tags, values, total));
        });
        return result;
    }

    private static Map<String, List<String>> counters() {
        Map<String, List<String>> counters = new LinkedHashMap<>();
        counters.put(Metrics.HOLD_GATE, List.of(Tag.RESULT));
        counters.put(Metrics.HOLD_DB, List.of(Tag.RESULT));
        counters.put(Metrics.HOLD_FALLBACK, List.of(Tag.RESULT));
        counters.put(Metrics.HOLD_EXPIRED, List.of());
        counters.put(Metrics.SEATMAP_REBUILD, List.of(Tag.SOURCE));
        counters.put(Metrics.PAYMENT_RECONCILE, List.of(Tag.RESULT));
        counters.put(Metrics.OUTBOX_TASK, List.of(Tag.TYPE, Tag.RESULT));
        counters.put(Metrics.REDIS_REFLECT_FAILURE, List.of(Tag.OP));
        counters.put(Metrics.GATE_ORPHAN_REMOVED, List.of(Tag.KIND));
        counters.put(Metrics.GATE_ORPHAN_SKIPPED, List.of(Tag.REASON));
        return counters;
    }
}
