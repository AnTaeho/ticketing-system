package com.example.ticketing;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ticketing.hold.domain.HoldStatus;
import com.example.ticketing.hold.repository.SeatHoldJdbcRepository;
import com.example.ticketing.hold.repository.SeatHoldRepository;
import com.example.ticketing.seat.domain.SeatStatus;
import com.example.ticketing.seat.repository.SeatJdbcRepository;
import com.example.ticketing.seat.repository.SeatRepository;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

// SQL 문자열의 상태 리터럴은 컴파일러가 enum과 맞춰 주지 않으므로 여기서 묶는다
class SqlStatusLiteralTest {

    private static final Pattern STATUS_CLAUSE =
            Pattern.compile("status\\s*(?:=|IN)\\s*\\(?\\s*('[A-Z_]+'(?:\\s*,\\s*'[A-Z_]+')*)");
    private static final Pattern LITERAL = Pattern.compile("'([A-Z_]+)'");

    @Test
    @DisplayName("hold·seat 저장소 SQL의 상태 리터럴은 모두 그 테이블 상태 enum의 이름이다")
    void statusLiteralsAreEnumNames() {
        assertLiterals(sqlOf(SeatHoldRepository.class), HoldStatus.class);
        assertLiterals(sqlOf(SeatHoldJdbcRepository.class), HoldStatus.class);
        assertLiterals(sqlOf(SeatRepository.class), SeatStatus.class);
        assertLiterals(sqlOf(SeatJdbcRepository.class), SeatStatus.class);
    }

    @Test
    @DisplayName("hold·seat 저장소의 조건부 UPDATE는 모두 flush·clear 옵션을 켠다")
    void modifyingOptionsAreConsistent() {
        List<Method> modifying = new ArrayList<>();
        for (Class<?> repository : List.of(SeatHoldRepository.class, SeatRepository.class)) {
            Arrays.stream(repository.getDeclaredMethods())
                    .filter(method -> method.isAnnotationPresent(Modifying.class))
                    .forEach(modifying::add);
        }

        assertThat(modifying).isNotEmpty().allSatisfy(method -> {
            Modifying options = method.getAnnotation(Modifying.class);
            assertThat(options.clearAutomatically()).as(method.getName()).isTrue();
            assertThat(options.flushAutomatically()).as(method.getName()).isTrue();
        });
    }

    private static void assertLiterals(List<String> sqls, Class<? extends Enum<?>> statusType) {
        List<String> names = Arrays.stream(statusType.getEnumConstants()).map(Enum::name).toList();
        List<String> literals = new ArrayList<>();
        for (String sql : sqls) {
            Matcher clause = STATUS_CLAUSE.matcher(sql);
            while (clause.find()) {
                Matcher literal = LITERAL.matcher(clause.group(1));
                while (literal.find()) {
                    literals.add(literal.group(1));
                }
            }
        }
        assertThat(literals).as(statusType.getSimpleName() + " 리터럴").isNotEmpty();
        assertThat(names).as(statusType.getSimpleName()).containsAll(literals);
    }

    // JPA 저장소는 @Query 값을, JDBC 저장소는 static SQL 상수를 읽는다
    private static List<String> sqlOf(Class<?> repository) {
        List<String> sqls = new ArrayList<>();
        for (Method method : repository.getDeclaredMethods()) {
            Query query = method.getAnnotation(Query.class);
            if (query != null) {
                sqls.add(query.value());
            }
        }
        for (Field field : repository.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == String.class) {
                field.setAccessible(true);
                try {
                    sqls.add((String) field.get(null));
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException(e);
                }
            }
        }
        assertThat(sqls).as(repository.getSimpleName() + " SQL").isNotEmpty();
        return sqls;
    }
}
