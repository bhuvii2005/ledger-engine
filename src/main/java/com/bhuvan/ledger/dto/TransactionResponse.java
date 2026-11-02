package com.bhuvan.ledger.dto;

import java.time.Instant;
import java.util.UUID;

/** Body of a successful deposit, withdrawal or transfer (201). */
public record TransactionResponse(
        UUID transactionId,
        String type,
        String amount,
        String currency,
        String fromAccount,
        String toAccount,
        String fromBalanceAfter,
        String toBalanceAfter,
        UUID reversesTransactionId,
        Instant createdAt) {
}
