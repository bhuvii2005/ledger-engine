package com.bhuvan.ledger.util;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

public final class Timestamps {

    private Timestamps() {
    }

    // Postgres TIMESTAMPTZ keeps microseconds; truncating here means the value we
    // return right after a write is the same value a later read returns.
    public static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
