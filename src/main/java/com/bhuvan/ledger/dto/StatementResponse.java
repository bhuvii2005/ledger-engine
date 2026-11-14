package com.bhuvan.ledger.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One page of an account's ledger entries, newest first. Pass nextBeforeId as beforeId
 * to get the next (older) page; it is null on the last page.
 */
public record StatementResponse(
        String accountNumber,
        List<Entry> entries,
        Long nextBeforeId) {

    public record Entry(
            long entryId,
            UUID transactionId,
            String type,
            String direction,
            String amount,
            String balanceAfter,
            String description,
            Instant createdAt) {
    }
}
