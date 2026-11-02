package com.bhuvan.ledger.dto;

import com.bhuvan.ledger.entity.Account;
import com.bhuvan.ledger.entity.AccountStatus;
import com.bhuvan.ledger.entity.AccountType;
import com.bhuvan.ledger.util.Money;
import java.time.Instant;

public record AccountResponse(
        String accountNumber,
        String ownerName,
        AccountType type,
        String currency,
        String balance,
        AccountStatus status,
        Instant createdAt,
        Instant updatedAt) {

    public static AccountResponse from(Account account) {
        return new AccountResponse(
                account.getAccountNumber(),
                account.getOwnerName(),
                account.getAccountType(),
                account.getCurrency(),
                Money.format(account.getBalance()),
                account.getStatus(),
                account.getCreatedAt(),
                account.getUpdatedAt());
    }
}
