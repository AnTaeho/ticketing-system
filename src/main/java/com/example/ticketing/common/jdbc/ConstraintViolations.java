package com.example.ticketing.common.jdbc;

import org.hibernate.exception.ConstraintViolationException;

public final class ConstraintViolations {

    private ConstraintViolations() {
    }

    // 원인 사슬 어디든 제약 이름이나 드라이버 메시지에 그 이름이 있으면 위반으로 본다. 감싼 층마다 이름을 싣는 곳이 달라서다
    public static boolean matches(Throwable e, String constraintName) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation && violation.getConstraintName() != null
                    && violation.getConstraintName().contains(constraintName)) {
                return true;
            }
            if (cause.getMessage() != null && cause.getMessage().contains(constraintName)) {
                return true;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return false;
    }
}
