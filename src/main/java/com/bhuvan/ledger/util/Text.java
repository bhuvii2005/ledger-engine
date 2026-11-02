package com.bhuvan.ledger.util;

import java.util.regex.Pattern;

public final class Text {

    private static final Pattern CONTROL_CHARS = Pattern.compile("\\p{Cntrl}");

    private Text() {
    }

    /** Drops control characters and surrounding whitespace; blank becomes null. */
    public static String cleanOptional(String value) {
        if (value == null) {
            return null;
        }
        String cleaned = CONTROL_CHARS.matcher(value).replaceAll("").strip();
        return cleaned.isEmpty() ? null : cleaned;
    }
}
