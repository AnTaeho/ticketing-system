package com.example.ticketing;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ticketing.booking.domain.BookingStatus;
import com.example.ticketing.booking.repository.BookingJdbcRepository;
import com.example.ticketing.booking.repository.BookingRepository;
import com.example.ticketing.booking.repository.BookingSeatRepository;
import com.example.ticketing.hold.domain.HoldStatus;
import com.example.ticketing.hold.repository.SeatHoldJdbcRepository;
import com.example.ticketing.hold.repository.SeatHoldRepository;
import com.example.ticketing.ops.repository.OpsJdbcRepository;
import com.example.ticketing.outbox.domain.OutboxTaskStatus;
import com.example.ticketing.outbox.domain.OutboxTaskType;
import com.example.ticketing.outbox.repository.OutboxTaskJdbcRepository;
import com.example.ticketing.payment.domain.PaymentStatus;
import com.example.ticketing.payment.repository.PaymentJdbcRepository;
import com.example.ticketing.payment.repository.PaymentRepository;
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

    private static final String STATUS_LIST = "\\s*(?:=|IN)\\s*\\(?\\s*('[A-Z_]+'(?:\\s*,\\s*'[A-Z_]+')*)";
    private static final Pattern STATUS_CLAUSE = Pattern.compile("status" + STATUS_LIST);
    private static final Pattern IN_LIST = Pattern.compile("status\\s+IN\\s*\\(\\s*('[A-Z_]+'(?:\\s*,\\s*'[A-Z_]+')*)");
    private static final Pattern LITERAL = Pattern.compile("'([A-Z_]+)'");

    @Test
    @DisplayName("저장소 SQL의 상태 리터럴은 모두 그 테이블 상태 enum의 이름이다")
    void statusLiteralsAreEnumNames() {
        assertLiterals(sqlOf(SeatHoldRepository.class), HoldStatus.class);
        // 게이트 키 대사는 별칭 s로 좌석 상태를 읽는다
        List<String> holdJdbc = sqlOf(SeatHoldJdbcRepository.class);
        assertEnumNames(literals(holdJdbc, Pattern.compile("(?<!s\\.)\\bstatus" + STATUS_LIST)), HoldStatus.class);
        assertEnumNames(literals(holdJdbc, Pattern.compile("\\bs\\.status" + STATUS_LIST)), SeatStatus.class);
        assertLiterals(sqlOf(SeatRepository.class), SeatStatus.class);
        assertLiterals(sqlOf(SeatJdbcRepository.class), SeatStatus.class);
        assertLiterals(sqlOf(PaymentRepository.class), PaymentStatus.class);
        assertLiterals(sqlOf(PaymentJdbcRepository.class), PaymentStatus.class);
        assertLiterals(sqlOf(BookingRepository.class), BookingStatus.class);
        // booking_seat 복사는 seat 테이블의 SOLD를 읽고, 예매 조회는 별칭 bp로 결제 상태를 읽는다
        assertLiterals(sqlOf(BookingSeatRepository.class), SeatStatus.class);
        assertEnumNames(literals(sqlOf(BookingJdbcRepository.class), Pattern.compile("\\bbp\\.status" + STATUS_LIST)),
                PaymentStatus.class);
        assertThat(literals(sqlOf(BookingJdbcRepository.class), Pattern.compile("(?<!bp\\.)\\bstatus" + STATUS_LIST)))
                .as("BookingJdbcRepository의 별칭 없는 상태 비교").isEmpty();
        assertLiterals(sqlOf(OutboxTaskJdbcRepository.class), OutboxTaskStatus.class);
    }

    @Test
    @DisplayName("운영 집계 SQL의 상태·작업 종류 리터럴은 별칭이 가리키는 테이블의 enum 이름이다")
    void opsLiteralsAreEnumNames() {
        List<String> sqls = sqlOf(OpsJdbcRepository.class);

        assertEnumNames(literals(sqls, Pattern.compile("\\bbp\\.status" + STATUS_LIST)), PaymentStatus.class);
        assertEnumNames(literals(sqls, Pattern.compile("\\bh\\.status" + STATUS_LIST)), HoldStatus.class);
        assertEnumNames(literals(sqls, Pattern.compile("\\bo\\.status" + STATUS_LIST)), OutboxTaskStatus.class);
        assertEnumNames(literals(sqls, Pattern.compile("\\bo\\.task_type" + STATUS_LIST)), OutboxTaskType.class);
        assertThat(literals(sqls, Pattern.compile("(?<![a-z]\\.)\\bstatus" + STATUS_LIST)))
                .as("OpsJdbcRepository의 별칭 없는 상태 비교").isEmpty();
    }

    @Test
    @DisplayName("결제 상태 IN 목록은 모두 PaymentStatus.isOpen()인 상태와 같다")
    void openPaymentStatusListsMatchEnum() {
        List<String> open = Arrays.stream(PaymentStatus.values()).filter(PaymentStatus::isOpen).map(Enum::name)
                .toList();
        List<String> sqls = new ArrayList<>(sqlOf(PaymentRepository.class));
        sqls.addAll(sqlOf(PaymentJdbcRepository.class));
        sqls.addAll(sqlOf(OpsJdbcRepository.class));
        List<List<String>> inLists = new ArrayList<>();
        for (String sql : sqls) {
            Matcher clause = IN_LIST.matcher(sql);
            while (clause.find()) {
                inLists.add(literals(List.of(clause.group()), IN_LIST));
            }
        }

        assertThat(inLists).isNotEmpty()
                .allSatisfy(list -> assertThat(list).containsExactlyInAnyOrderElementsOf(open));
    }

    @Test
    @DisplayName("저장소의 조건부 UPDATE는 모두 flush·clear 옵션을 켠다")
    void modifyingOptionsAreConsistent() {
        List<Method> modifying = new ArrayList<>();
        for (Class<?> repository : List.of(SeatHoldRepository.class, SeatRepository.class, PaymentRepository.class,
                BookingRepository.class, BookingSeatRepository.class)) {
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
        assertEnumNames(literals(sqls, STATUS_CLAUSE), statusType);
    }

    private static void assertEnumNames(List<String> literals, Class<? extends Enum<?>> statusType) {
        List<String> names = Arrays.stream(statusType.getEnumConstants()).map(Enum::name).toList();
        assertThat(literals).as(statusType.getSimpleName() + " 리터럴").isNotEmpty();
        assertThat(names).as(statusType.getSimpleName()).containsAll(literals);
    }

    private static List<String> literals(List<String> sqls, Pattern statusClause) {
        List<String> literals = new ArrayList<>();
        for (String sql : sqls) {
            Matcher clause = statusClause.matcher(sql);
            while (clause.find()) {
                Matcher literal = LITERAL.matcher(clause.group(1));
                while (literal.find()) {
                    literals.add(literal.group(1));
                }
            }
        }
        return literals;
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
