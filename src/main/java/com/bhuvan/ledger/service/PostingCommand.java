package com.bhuvan.ledger.service;

import com.bhuvan.ledger.entity.TransactionType;
import com.bhuvan.ledger.util.RequestHasher;
import java.util.UUID;

/**
 * One money movement, already validated for shape: amount parsed to paise,
 * description cleaned. Source is always the account that gets debited.
 */
public record PostingCommand(
        TransactionType operation,
        String idempotencyKey,
        String requestHash,
        String sourceAccountNumber,
        String destAccountNumber,
        long amountPaise,
        String currency,
        String description,
        UUID reversesTxnId) {

    public static PostingCommand of(TransactionType operation, String idempotencyKey,
            String sourceAccountNumber, String destAccountNumber, long amountPaise,
            String currency, String description, UUID reversesTxnId) {
        String hash = RequestHasher.hash(operation.name(), sourceAccountNumber, destAccountNumber,
                amountPaise, currency, description, reversesTxnId);
        return new PostingCommand(operation, idempotencyKey, hash, sourceAccountNumber, destAccountNumber,
                amountPaise, currency, description, reversesTxnId);
    }
}
