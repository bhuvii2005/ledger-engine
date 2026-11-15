package com.bhuvan.ledger.service;

import com.bhuvan.ledger.exception.ErrorCode;
import com.bhuvan.ledger.exception.LedgerException;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * Redis fast path in front of the Postgres idempotency table. Postgres is the source of
 * truth; this only answers repeats quickly and turns concurrent duplicates into a 409
 * instead of making them queue on the key's unique index.
 *
 * <p>Every Redis call goes through {@link #safely}: any error counts as a cache miss, so a
 * broken Redis sends requests down the DB path, which is still exactly-once.
 */
@Slf4j
@Component
public class IdempotencyCache {

    static final String KEY_PREFIX = "idem:";

    // delete the IN_PROGRESS marker only if it is still the one this request wrote
    private static final RedisScript<Long> DELETE_IF_SAME = RedisScript.of(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final Counter failures;
    private final Duration inProgressTtl;
    private final Duration completedTtl;
    private final long skipAfterFailureMs;
    private final long warnIntervalMs;

    private final AtomicLong skipUntil = new AtomicLong();
    private final AtomicLong lastWarnAt = new AtomicLong(Long.MIN_VALUE);
    private final AtomicLong failuresSinceWarn = new AtomicLong();

    public IdempotencyCache(StringRedisTemplate redis, ObjectMapper objectMapper, MeterRegistry meterRegistry,
            @Value("${ledger.idempotency.in-progress-ttl}") Duration inProgressTtl,
            @Value("${ledger.idempotency.completed-ttl}") Duration completedTtl,
            @Value("${ledger.idempotency.redis-skip-after-failure}") Duration skipAfterFailure,
            @Value("${ledger.idempotency.redis-warn-interval}") Duration warnInterval) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.failures = meterRegistry.counter("ledger.redis.failures");
        this.inProgressTtl = inProgressTtl;
        this.completedTtl = completedTtl;
        this.skipAfterFailureMs = skipAfterFailure.toMillis();
        this.warnIntervalMs = warnInterval.toMillis();
    }

    /**
     * Replay from Redis if the key is COMPLETED for this same request. Throws 409 if the same
     * request is still in flight, 422 if the key belongs to a different request. Empty means
     * "not in Redis (or Redis unavailable), ask Postgres".
     */
    public Optional<PostingResult> lookup(PostingCommand cmd) {
        Optional<Entry> found = safely("lookup", () -> read(cmd.idempotencyKey()));
        if (found.isEmpty()) {
            return Optional.empty();
        }
        Entry entry = found.get();
        if (!entry.hash().equals(cmd.requestHash())) {
            throw new LedgerException(ErrorCode.IDEMPOTENCY_KEY_REUSED,
                    "Idempotency-Key " + cmd.idempotencyKey() + " was already used for a different request");
        }
        if (Entry.IN_PROGRESS.equals(entry.state())) {
            throw new LedgerException(ErrorCode.REQUEST_IN_PROGRESS,
                    "A request with this Idempotency-Key is still being processed");
        }
        return Optional.of(new PostingResult(entry.httpStatus(), entry.body(), entry.transactionId(), true));
    }

    /**
     * SET NX with a short TTL. The TTL only has to outlive one posting with all its retries; if
     * it expires early nothing breaks, Postgres still guards the key.
     *
     * <p>A SET NX that errors or times out may still have been applied: Redis ran it and only
     * the reply was lost. So after an error the key is read back once. If it holds the marker
     * this call wrote (same owner token), the key is ours. Treating it as someone else's would
     * answer 409 to a request that was only ever sent once.
     */
    public Claim tryMarkInProgress(PostingCommand cmd) {
        String owner = UUID.randomUUID().toString();
        String marker = toJson(new Entry(Entry.IN_PROGRESS, cmd.requestHash(), owner, null, null, null));
        long now = System.currentTimeMillis();
        if (now < skipUntil.get()) {
            return Claim.MISS;
        }
        try {
            Boolean set = redis.opsForValue().setIfAbsent(key(cmd), marker, inProgressTtl);
            return Boolean.TRUE.equals(set) ? Claim.claimed(marker) : Claim.TAKEN;
        } catch (RuntimeException e) {
            recordFailure("mark", e, now);
            return checkOwnMarker(cmd, owner, marker);
        }
    }

    // Runs straight after a failed SET NX, so it deliberately ignores the skip window that
    // failure has just opened: one read decides whether this request already holds the key.
    private Claim checkOwnMarker(PostingCommand cmd, String owner, String marker) {
        Optional<Entry> current;
        try {
            current = read(cmd.idempotencyKey());
        } catch (RuntimeException e) {
            recordFailure("mark check", e, System.currentTimeMillis());
            return Claim.MISS; // can't tell: no marker, Postgres decides
        }
        // Redis answered, so the failure was transient. Close the skip window again so that
        // storeCompleted or clearInProgress for this request reach Redis instead of being
        // skipped and leaving the marker behind for its full TTL.
        skipUntil.set(0);
        if (current.isEmpty()) {
            return Claim.MISS; // the SET was not applied (or the value is unreadable)
        }
        Entry entry = current.get();
        if (Entry.IN_PROGRESS.equals(entry.state()) && owner.equals(entry.owner())) {
            log.info("SET NX for key {} failed but had been applied; continuing as its owner",
                    cmd.idempotencyKey());
            return Claim.claimed(marker);
        }
        return Claim.TAKEN; // another request's marker or a completed entry: lookup decides
    }

    /** Called only after PostingService has returned, so the DB transaction has committed. */
    public void storeCompleted(PostingCommand cmd, PostingResult result) {
        String value = toJson(new Entry(Entry.COMPLETED, cmd.requestHash(), null,
                result.httpStatus(), result.body(), result.transactionId()));
        safely("store", () -> {
            redis.opsForValue().set(key(cmd), value, completedTtl);
            return Optional.of(true);
        });
    }

    public void clearInProgress(PostingCommand cmd, String marker) {
        safely("clear", () -> Optional.ofNullable(redis.execute(DELETE_IF_SAME, List.of(key(cmd)), marker)));
    }

    private Optional<Entry> read(String idempotencyKey) {
        String json = redis.opsForValue().get(KEY_PREFIX + idempotencyKey);
        if (json == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(json, Entry.class));
        } catch (Exception e) {
            // Redis itself is fine, so no skip window: treat it as a miss and let Postgres answer.
            // A completed posting overwrites the bad value afterwards.
            failures.increment();
            log.warn("Unreadable idempotency entry in Redis for key {}; ignoring it", idempotencyKey);
            return Optional.empty();
        }
    }

    private <T> Optional<T> safely(String operation, Supplier<Optional<T>> call) {
        long now = System.currentTimeMillis();
        if (now < skipUntil.get()) {
            // Redis failed a moment ago; don't make every request wait out another timeout
            return Optional.empty();
        }
        try {
            return call.get();
        } catch (RuntimeException e) {
            recordFailure(operation, e, now);
            return Optional.empty();
        }
    }

    private void recordFailure(String operation, RuntimeException e, long now) {
        failures.increment();
        skipUntil.set(now + skipAfterFailureMs);
        warnAtMostOncePerInterval(operation, e, now);
    }

    private void warnAtMostOncePerInterval(String operation, RuntimeException e, long now) {
        long suppressed = failuresSinceWarn.incrementAndGet();
        long last = lastWarnAt.get();
        if ((last == Long.MIN_VALUE || now - last >= warnIntervalMs) && lastWarnAt.compareAndSet(last, now)) {
            failuresSinceWarn.set(0);
            log.warn("Redis unavailable during {} ({}: {}); using Postgres for idempotency. "
                    + "{} Redis failure(s) since the last warning", operation, e.getClass().getSimpleName(),
                    e.getMessage(), suppressed);
        }
    }

    private static String key(PostingCommand cmd) {
        return KEY_PREFIX + cmd.idempotencyKey();
    }

    private String toJson(Entry entry) {
        try {
            return objectMapper.writeValueAsString(entry);
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialize idempotency entry", e);
        }
    }

    /**
     * Outcome of {@link #tryMarkInProgress}. CLAIMED: this request holds the key and carries
     * the marker it wrote. TAKEN: another request's marker or a completed entry is there, so
     * look it up again. MISS: Redis can't say (down, skipped, or the SET was not applied), so
     * carry on unmarked and let Postgres serialize duplicates on the key's unique index.
     */
    public record Claim(Outcome outcome, String marker) {

        public enum Outcome { CLAIMED, TAKEN, MISS }

        public static final Claim TAKEN = new Claim(Outcome.TAKEN, null);
        public static final Claim MISS = new Claim(Outcome.MISS, null);

        public static Claim claimed(String marker) {
            return new Claim(Outcome.CLAIMED, marker);
        }

        public Optional<String> ownMarker() {
            return Optional.ofNullable(marker);
        }
    }

    /**
     * Value stored at idem:{key}. {@code owner} is random per IN_PROGRESS marker so a request
     * only ever clears its own marker, never a newer one written after its TTL ran out.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Entry(String state, String hash, String owner, Integer httpStatus, String body, UUID transactionId) {
        static final String IN_PROGRESS = "IN_PROGRESS";
        static final String COMPLETED = "COMPLETED";
    }
}
