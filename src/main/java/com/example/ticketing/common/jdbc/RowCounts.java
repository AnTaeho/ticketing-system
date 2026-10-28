package com.example.ticketing.common.jdbc;

// 조건부 UPDATE의 영향 행 수 확인. 업무 실패는 호출자가 boolean을 보고 ApiException으로 바꾼다
public final class RowCounts {

    private RowCounts() {
    }

    // false면 그 사이 다른 쪽이 먼저 바꿨거나 조건이 맞지 않았다
    public static boolean changedOne(Integer actual) {
        return actual != null && actual == 1;
    }

    public static boolean changedExactly(int actual, int expected) {
        return actual == expected;
    }

    // 락 아래라 어긋날 수 없는 변경에 쓴다. 어긋나면 롤백해 반쯤 바뀐 상태를 남기지 않는다
    public static void requireExactly(int actual, int expected, String what) {
        if (actual != expected) {
            throw new IllegalStateException(what + " 영향 행 수: 기대 " + expected + ", 실제 " + actual);
        }
    }
}
