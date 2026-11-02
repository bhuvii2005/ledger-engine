package com.bhuvan.ledger.exception;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ErrorCodeTest {

    @ParameterizedTest
    @CsvSource({
            "VALIDATION_FAILED, 400",
            "MALFORMED_REQUEST, 400",
            "MISSING_IDEMPOTENCY_KEY, 400",
            "INVALID_IDEMPOTENCY_KEY, 400",
            "ACCOUNT_NOT_FOUND, 404",
            "TRANSACTION_NOT_FOUND, 404",
            "NOT_FOUND, 404",
            "METHOD_NOT_ALLOWED, 405",
            "REQUEST_IN_PROGRESS, 409",
            "ALREADY_REVERSED, 409",
            "UNSUPPORTED_MEDIA_TYPE, 415",
            "INSUFFICIENT_FUNDS, 422",
            "SAME_ACCOUNT_TRANSFER, 422",
            "ACCOUNT_NOT_ACTIVE, 422",
            "ACCOUNT_CLOSED, 422",
            "ACCOUNT_HAS_BALANCE, 422",
            "SYSTEM_ACCOUNT_IMMUTABLE, 422",
            "CURRENCY_NOT_SUPPORTED, 422",
            "AMOUNT_LIMIT_EXCEEDED, 422",
            "CANNOT_REVERSE_REVERSAL, 422",
            "IDEMPOTENCY_KEY_REUSED, 422",
            "SYSTEM_BUSY, 503",
            "INTERNAL_ERROR, 500"
    })
    void mapsToTheDocumentedHttpStatus(ErrorCode code, int expectedStatus) {
        assertThat(code.status().value()).isEqualTo(expectedStatus);
    }

    @Test
    void everyCodeIsCoveredAbove() {
        // forces the table above to be updated whenever a code is added
        assertThat(ErrorCode.values()).hasSize(23);
    }
}
