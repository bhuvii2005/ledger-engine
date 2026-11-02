package com.bhuvan.ledger.dto;

import com.bhuvan.ledger.entity.AccountStatus;
import jakarta.validation.constraints.NotNull;

public record UpdateAccountStatusRequest(
        @NotNull(message = "status is required")
        AccountStatus status) {
}
