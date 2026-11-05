package com.bhuvan.ledger.dto;

import com.bhuvan.ledger.util.Text;
import jakarta.validation.constraints.Size;

/** Optional body of POST /transactions/{id}/reverse. The reason becomes the reversal's description. */
public record ReverseRequest(
        @Size(max = 255, message = "reason must be at most 255 characters")
        String reason) {

    public ReverseRequest {
        reason = Text.cleanOptional(reason);
    }
}
