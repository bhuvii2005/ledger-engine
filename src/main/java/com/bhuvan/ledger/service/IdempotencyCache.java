package com.bhuvan.ledger.service;

import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Redis fast path for idempotency keys. Not wired to Redis yet: every method is
 * a no-op, so all requests take the Postgres path, which is the real guard anyway.
 */
@Component
public class IdempotencyCache {

    public Optional<PostingResult> lookup(PostingCommand cmd) {
        return Optional.empty();
    }

    public boolean tryMarkInProgress(PostingCommand cmd) {
        return false;
    }

    public void storeCompleted(PostingCommand cmd, PostingResult result) {
        // no-op until the Redis cache exists
    }

    public void clearInProgress(PostingCommand cmd) {
        // no-op until the Redis cache exists
    }
}
