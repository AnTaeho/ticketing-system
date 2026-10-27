package com.example.ticketing.booking.show;

import com.example.ticketing.booking.seat.domain.SeatStatus;
import com.example.ticketing.booking.show.SeatLayout.SeatSpec;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

// JDBC URL 옵션(rewriteBatchedStatements)에 기대지 않고 다중 VALUES 문장을 직접 만든다
@Component
@RequiredArgsConstructor
public class SeatBulkInserter {

    static final int CHUNK_SIZE = 200;

    private static final String INSERT_PREFIX =
            "INSERT INTO seat (performance_id, section, row_no, seat_no, grade, price, status) VALUES ";
    private static final String ROW_PLACEHOLDER = "(?, ?, ?, ?, ?, ?, ?)";
    private static final int COLUMN_COUNT = 7;

    private final JdbcTemplate jdbcTemplate;

    // 회차 INSERT와 같은 트랜잭션에서만 부른다
    @Transactional(propagation = Propagation.MANDATORY)
    public int insert(long performanceId, List<SeatSpec> specs) {
        int inserted = 0;
        for (int from = 0; from < specs.size(); from += CHUNK_SIZE) {
            List<SeatSpec> chunk = specs.subList(from, Math.min(from + CHUNK_SIZE, specs.size()));
            inserted += jdbcTemplate.update(sql(chunk.size()), args(performanceId, chunk));
        }
        return inserted;
    }

    private static String sql(int rows) {
        StringBuilder sql = new StringBuilder(INSERT_PREFIX.length() + rows * (ROW_PLACEHOLDER.length() + 2))
                .append(INSERT_PREFIX);
        for (int i = 0; i < rows; i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append(ROW_PLACEHOLDER);
        }
        return sql.toString();
    }

    private static Object[] args(long performanceId, List<SeatSpec> chunk) {
        Object[] args = new Object[chunk.size() * COLUMN_COUNT];
        int i = 0;
        for (SeatSpec spec : chunk) {
            args[i++] = performanceId;
            args[i++] = spec.section();
            args[i++] = spec.rowNo();
            args[i++] = spec.seatNo();
            args[i++] = spec.grade().name();
            args[i++] = spec.price();
            args[i++] = SeatStatus.AVAILABLE.name();
        }
        return args;
    }
}
