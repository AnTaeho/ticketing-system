package com.example.ticketing.common.text;

public final class Texts {

    private Texts() {
    }

    // 말줄임표까지 maxLength자 안에 넣는다. 컬럼 길이를 넘기지 않으려고 쓴다
    public static String abbreviate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength - 1) + "…";
    }
}
