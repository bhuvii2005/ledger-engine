package com.bhuvan.ledger.dto;

import com.bhuvan.ledger.util.Text;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Deposit or withdrawal: money moves between this account and the system cash account. */
public record CashRequest(
        @NotBlank(message = "accountNumber is required")
        @Size(max = 20, message = "accountNumber must be at most 20 characters")
        String accountNumber,

        @NotNull(message = "amount is required")
        @ValidAmount
        String amount,

        @Size(max = 255, message = "description must be at most 255 characters")
        String description) {

    public CashRequest {
        description = Text.cleanOptional(description);
    }
}
