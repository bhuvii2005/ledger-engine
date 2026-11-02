package com.bhuvan.ledger.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A stored transaction with both of its ledger entries (GET /transactions/{id}). */
public record TransactionDetailsResponse(
        UUID transactionId,
        String type,
        String amount,
        String currency,
        String description,
        String fromAccount,
        String toAccount,
        UUID reversesTransactionId,
        Instant createdAt,
        List<Entry> entries) {

    public record Entry(
            long entryId,
            String accountNumber,
            String direction,
            String amount,
            String balanceAfter,
            Instant createdAt) {
    }
}
