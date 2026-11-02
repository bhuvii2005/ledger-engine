package com.bhuvan.ledger.dto;

import com.bhuvan.ledger.util.Text;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record TransferRequest(
        @NotBlank(message = "fromAccount is required")
        @Size(max = 20, message = "fromAccount must be at most 20 characters")
        String fromAccount,

        @NotBlank(message = "toAccount is required")
        @Size(max = 20, message = "toAccount must be at most 20 characters")
        String toAccount,

        @NotNull(message = "amount is required")
        @ValidAmount
        String amount,

        // optional; anything other than INR is a 422 from the service, not a 400 here
        @Size(max = 10, message = "currency must be at most 10 characters")
        String currency,

        @Size(max = 255, message = "description must be at most 255 characters")
        String description) {

    public TransferRequest {
        currency = currency == null ? "INR" : currency;
        description = Text.cleanOptional(description);
    }
}
