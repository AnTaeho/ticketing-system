package com.example.ticketing.common.jdbc;

// 락 아래라 어긋날 수 없는 변경의 영향 행 수 확인. 어긋나면 롤백해 반쯤 바뀐 상태를 남기지 않는다
public final class RowCounts {

    private RowCounts() {
    }

    public static void requireExactly(int actual, int expected, String what) {
        if (actual != expected) {
            throw new IllegalStateException(what + " 영향 행 수: 기대 " + expected + ", 실제 " + actual);
        }
    }
}
