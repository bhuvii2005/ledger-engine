package com.bhuvan.ledger.exception;

import org.springframework.http.HttpStatus;

public enum ErrorCode {

    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Validation failed"),
    MALFORMED_REQUEST(HttpStatus.BAD_REQUEST, "Malformed request"),
    MISSING_IDEMPOTENCY_KEY(HttpStatus.BAD_REQUEST, "Missing Idempotency-Key header"),
    INVALID_IDEMPOTENCY_KEY(HttpStatus.BAD_REQUEST, "Invalid Idempotency-Key header"),

    ACCOUNT_NOT_FOUND(HttpStatus.NOT_FOUND, "Account not found"),
    TRANSACTION_NOT_FOUND(HttpStatus.NOT_FOUND, "Transaction not found"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "Not found"),

    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "Method not allowed"),

    REQUEST_IN_PROGRESS(HttpStatus.CONFLICT, "Request with this key is still in progress"),
    ALREADY_REVERSED(HttpStatus.CONFLICT, "Transaction already reversed"),

    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported media type"),

    INSUFFICIENT_FUNDS(HttpStatus.UNPROCESSABLE_ENTITY, "Insufficient funds"),
    SAME_ACCOUNT_TRANSFER(HttpStatus.UNPROCESSABLE_ENTITY, "Source and destination are the same account"),
    ACCOUNT_NOT_ACTIVE(HttpStatus.UNPROCESSABLE_ENTITY, "Account is not active"),
    ACCOUNT_CLOSED(HttpStatus.UNPROCESSABLE_ENTITY, "Account is closed"),
    ACCOUNT_HAS_BALANCE(HttpStatus.UNPROCESSABLE_ENTITY, "Account balance must be zero to close"),
    SYSTEM_ACCOUNT_IMMUTABLE(HttpStatus.UNPROCESSABLE_ENTITY, "System accounts cannot be changed through this endpoint"),
    CURRENCY_NOT_SUPPORTED(HttpStatus.UNPROCESSABLE_ENTITY, "Only INR is supported"),
    AMOUNT_LIMIT_EXCEEDED(HttpStatus.UNPROCESSABLE_ENTITY, "Amount exceeds the per-transaction limit"),
    CANNOT_REVERSE_REVERSAL(HttpStatus.UNPROCESSABLE_ENTITY, "A reversal cannot be reversed"),
    IDEMPOTENCY_KEY_REUSED(HttpStatus.UNPROCESSABLE_ENTITY, "Idempotency-Key was already used for a different request"),

    SYSTEM_BUSY(HttpStatus.SERVICE_UNAVAILABLE, "System busy, retry shortly"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error");

    private final HttpStatus status;
    private final String title;

    ErrorCode(HttpStatus status, String title) {
        this.status = status;
        this.title = title;
    }

    public HttpStatus status() {
        return status;
    }

    public String title() {
        return title;
    }
}
