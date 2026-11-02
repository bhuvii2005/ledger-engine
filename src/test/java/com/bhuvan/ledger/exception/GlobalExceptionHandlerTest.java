package com.bhuvan.ledger.exception;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void unexpectedErrorNeverLeaksTheMessageAndReturnsACorrelationId() {
        ResponseEntity<ProblemDetail> response =
                handler.handleUnexpected(new IllegalStateException("SELECT * FROM accounts WHERE secret"));

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        ProblemDetail body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getDetail()).doesNotContain("SELECT").doesNotContain("secret");
        assertThat(body.getProperties()).containsEntry("code", "INTERNAL_ERROR").containsKey("correlationId");
    }

    @Test
    void ledgerExceptionKeepsItsCodeStatusAndProperties() {
        ResponseEntity<ProblemDetail> response =
                handler.handleLedger(LedgerException.accountNotFound("ACC0000000042"));

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody().getProperties())
                .containsEntry("code", "ACCOUNT_NOT_FOUND")
                .containsEntry("accountNumber", "ACC0000000042");
        assertThat(response.getHeaders().containsKey(HttpHeaders.RETRY_AFTER)).isFalse();
    }

    @Test
    void lockFailureBecomes503WithRetryAfter() {
        ResponseEntity<ProblemDetail> response =
                handler.handleDatabaseBusy(new CannotAcquireLockException("could not obtain lock on row"));

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
        assertThat(response.getBody().getProperties()).containsEntry("code", "SYSTEM_BUSY");
        assertThat(response.getBody().getDetail()).doesNotContain("lock on row");
    }
}
