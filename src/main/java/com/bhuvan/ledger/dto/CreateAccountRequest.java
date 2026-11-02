package com.bhuvan.ledger.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateAccountRequest(
        @NotBlank(message = "ownerName is required")
        @Size(max = 100, message = "ownerName must be at most 100 characters")
        @Pattern(regexp = "[^\\p{Cntrl}]*", message = "ownerName must not contain control characters")
        String ownerName) {

    // trim before validation runs, so "  Ravi  " is stored as "Ravi" and length counts real characters
    public CreateAccountRequest {
        ownerName = ownerName == null ? null : ownerName.strip();
    }
}
