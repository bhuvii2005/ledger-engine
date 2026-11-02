package com.bhuvan.ledger.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Hashes a canonical form of a money-moving request, so the same logical
 * request always hashes the same regardless of JSON field order, whitespace
 * or "1000" vs "1000.00".
 */
public final class RequestHasher {

    private RequestHasher() {
    }

    public static String hash(String operation, String sourceAccount, String destAccount,
            long amountPaise, String currency, String description, UUID reversesTxnId) {
        String canonical = String.join("|",
                escape(operation),
                escape(sourceAccount),
                escape(destAccount),
                Long.toString(amountPaise),
                escape(currency),
                escape(description),
                reversesTxnId == null ? "" : reversesTxnId.toString());
        return sha256Hex(canonical);
    }

    // free-text fields may contain '|', so escape it; otherwise "a|b" + "" and "a" + "b" would collide
    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("|", "\\|");
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available on the JVM", e);
        }
    }
}
