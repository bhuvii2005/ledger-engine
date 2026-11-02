package com.bhuvan.ledger.util;

import java.util.regex.Pattern;

/**
 * All conversion between API amount strings and paise lives here. Money is a
 * long count of paise everywhere else in the codebase.
 */
public final class Money {

    public static final String AMOUNT_REGEX = "^\\d{1,8}(\\.\\d{1,2})?$";
    private static final Pattern AMOUNT_PATTERN = Pattern.compile(AMOUNT_REGEX);

    private Money() {
    }

    /** Parses a positive amount like "1000", "1000.5" or "1000.50" into paise. */
    public static long parse(String amount) {
        if (amount == null || !AMOUNT_PATTERN.matcher(amount).matches()) {
            throw new IllegalArgumentException("Amount must look like 1000 or 1000.50");
        }
        int dot = amount.indexOf('.');
        String rupees = dot < 0 ? amount : amount.substring(0, dot);
        String fraction = dot < 0 ? "" : amount.substring(dot + 1);
        String paiseDigits = (fraction + "00").substring(0, 2);

        long paise = Math.addExact(
                Math.multiplyExact(Long.parseLong(rupees), 100L),
                Long.parseLong(paiseDigits));
        if (paise == 0) {
            throw new IllegalArgumentException("Amount must be greater than zero");
        }
        return paise;
    }

    /** Formats paise with exactly two decimals. Negative values are allowed (SYSTEM accounts). */
    public static String format(long paise) {
        // work on quotient and remainder separately so Long.MIN_VALUE can't overflow on negation
        long rupees = Math.abs(paise / 100);
        long rest = Math.abs(paise % 100);
        String sign = paise < 0 ? "-" : "";
        return sign + rupees + "." + (rest < 10 ? "0" : "") + rest;
    }

    public static long add(long a, long b) {
        return Math.addExact(a, b);
    }

    public static long subtract(long a, long b) {
        return Math.subtractExact(a, b);
    }
}
