package com.example.ticketing.common.error;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "입력한 내용을 다시 확인해 주세요"),
    IDEMPOTENCY_KEY_REQUIRED(HttpStatus.BAD_REQUEST, "결제 요청을 확인하지 못했어요. 화면을 새로 고친 뒤 다시 눌러 주세요"),
    INVALID_SEAT_SELECTION(HttpStatus.BAD_REQUEST, "고를 수 없는 자리가 섞여 있어요. 자리를 다시 골라 주세요"),

    LOGIN_REQUIRED(HttpStatus.UNAUTHORIZED, "먼저 닉네임을 입력하고 들어와 주세요"),

    PAYMENT_DECLINED(HttpStatus.PAYMENT_REQUIRED, "결제가 거절됐어요. 자리는 풀렸어요"),

    NOT_ADMITTED(HttpStatus.FORBIDDEN, "입장 순서가 지났어요. 다시 줄을 서 주세요"),

    SHOW_NOT_FOUND(HttpStatus.NOT_FOUND, "공연을 찾지 못했어요"),
    PERFORMANCE_NOT_FOUND(HttpStatus.NOT_FOUND, "공연 날짜를 찾지 못했어요"),
    HOLD_NOT_FOUND(HttpStatus.NOT_FOUND, "잡아 둔 자리를 찾지 못했어요"),
    PAYMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "결제 내역을 찾지 못했어요"),
    BOOKING_NOT_FOUND(HttpStatus.NOT_FOUND, "예매 내역을 찾지 못했어요"),
    QUEUE_TOKEN_NOT_FOUND(HttpStatus.NOT_FOUND, "줄 선 기록을 찾지 못했어요. 다시 줄을 서 주세요"),

    NOT_OPEN(HttpStatus.CONFLICT, "지금은 예매할 수 있는 시간이 아니에요"),
    SOLD_OUT(HttpStatus.CONFLICT, "모든 자리가 팔렸어요"),
    SEAT_TAKEN(HttpStatus.CONFLICT, "방금 다른 분이 고른 자리예요. 다른 자리를 골라 주세요"),
    SEAT_STATE_CHANGED(HttpStatus.CONFLICT, "방금 다른 분이 고른 자리예요. 다른 자리를 골라 주세요"),
    USER_HAS_HOLD(HttpStatus.CONFLICT, "이미 잡아 둔 자리가 있어요"),
    COOLDOWN(HttpStatus.CONFLICT, "시간이 지나 놓친 자리는 10분 뒤에 다시 고를 수 있어요"),
    HOLD_NOT_CANCELLABLE(HttpStatus.CONFLICT, "지금은 이 자리를 놓을 수 없어요"),
    HOLD_EXPIRED(HttpStatus.CONFLICT, "결제 시간이 지나 자리가 풀렸어요"),
    HOLD_NOT_PAYABLE(HttpStatus.CONFLICT, "지금은 이 자리를 결제할 수 없어요"),
    ALREADY_PAYING(HttpStatus.CONFLICT, "다른 화면에서 이미 결제하고 있어요"),
    SEAT_LOST_REFUNDING(HttpStatus.CONFLICT, "결제는 됐지만 자리를 지키지 못했어요. 결제 금액은 자동으로 돌려드려요"),
    PAYMENT_CANCELLED(HttpStatus.CONFLICT, "결제가 취소됐어요. 자리는 풀렸어요"),
    CANCEL_NOT_ALLOWED(HttpStatus.CONFLICT, "공연이 시작돼 예매를 취소할 수 없어요"),

    BUSY(HttpStatus.SERVICE_UNAVAILABLE, "사람이 많아 잠시 막혔어요. 잠시 뒤 다시 눌러 주세요"),
    QUEUE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "사람이 많아 잠시 막혔어요. 잠시 뒤 다시 눌러 주세요"),
    SEATMAP_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "자리 정보를 불러오지 못했어요. 잠시 뒤 다시 열어 주세요");

    private final HttpStatus status;
    private final String defaultMessage;
}
