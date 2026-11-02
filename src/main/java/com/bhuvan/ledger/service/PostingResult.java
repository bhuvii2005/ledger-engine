package com.bhuvan.ledger.service;

import java.util.UUID;

/**
 * What a posting attempt produced: the HTTP status and the exact JSON body that
 * was stored against the idempotency key, so a replay returns identical bytes.
 * transactionId is null for business rejections.
 */
public record PostingResult(int httpStatus, String body, UUID transactionId, boolean replayed) {

    public boolean isSuccess() {
        return httpStatus >= 200 && httpStatus < 300;
    }
}
