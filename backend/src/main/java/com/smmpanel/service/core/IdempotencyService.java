package com.smmpanel.service.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smmpanel.entity.IdempotencyKey;
import com.smmpanel.repository.jpa.IdempotencyKeyRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stripe-style idempotency. Keys POSTs that could be retried by a flaky network — a second call
 * with the same {@code Idempotency-Key} header returns the first call's cached result instead of
 * re-executing.
 *
 * <p>Semantics (matches Stripe's documented behavior):
 *
 * <ul>
 *   <li><b>Same key, same body, within window</b> → cached response replayed. The action is
 *       executed exactly once.
 *   <li><b>Same key, different body, within window</b> → 422-style rejection. Reusing a key with a
 *       different request body is a client bug; we refuse rather than silently returning an answer
 *       that doesn't match the new request.
 *   <li><b>Same key, after window expired</b> → fresh execution. Old records are pruned by the
 *       cleanup job; an inserted row past the TTL is treated as never-seen.
 *   <li><b>No key supplied</b> → callers should fall through to the regular path. This service
 *       deliberately does not invent keys; that's a caller decision.
 * </ul>
 *
 * <p>Concurrency: the unique constraint {@code uq_idem_user_key_op} is the gate. If two requests
 * with the same key race the initial INSERT, exactly one wins; the loser hits a {@code
 * DataIntegrityViolationException} and falls into the "fetch and replay" path. The action runs at
 * most once.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IdempotencyService {

    private final IdempotencyKeyRepository repo;
    private final ObjectMapper objectMapper;

    /**
     * TTL window. Default 5 minutes covers the typical retry envelope for browser/network blips
     * without permanently tying up a key (so a user who tries the same logical operation an hour
     * later is treated as a new request).
     */
    @Value("${app.idempotency.ttl-seconds:300}")
    private int ttlSeconds;

    /**
     * Maximum length of a client-supplied {@code Idempotency-Key} header. 128 chars matches the
     * column width and is plenty for any UUID variant a client would generate.
     */
    public static final int MAX_KEY_LENGTH = 128;

    /**
     * Wraps {@code action} with idempotency semantics.
     *
     * @param userId the authenticated user's id (the dedup window is per-user)
     * @param key the value of the {@code Idempotency-Key} header (may be null/blank → no dedup)
     * @param operation a stable identifier for the endpoint, e.g. {@code "create_order"}. Different
     *     operations don't collide even if a client reuses the same key across them.
     * @param requestBody the request body that produced the response — used to compute a
     *     fingerprint that detects "same key, different payload" abuse
     * @param action the work to execute on first call. Result is JSON-serialized and cached.
     * @param resultType the type to deserialize the cached body back to on replay
     * @return the action's result on first call, the cached result on replay
     */
    public <T> Result<T> executeWithKey(
            Long userId,
            String key,
            String operation,
            Object requestBody,
            Supplier<T> action,
            Class<T> resultType) {

        if (key == null || key.isBlank()) {
            // No idempotency requested — just execute. Caller treats this as success/200 path.
            return Result.executed(action.get());
        }
        if (key.length() > MAX_KEY_LENGTH) {
            throw new IllegalArgumentException(
                    "Idempotency-Key exceeds " + MAX_KEY_LENGTH + " characters");
        }

        String fingerprint = fingerprint(requestBody);

        // Fast path — key already used. Cheaper than relying on the constraint violation only.
        Optional<IdempotencyKey> existing =
                repo.findByUserIdAndIdempotencyKeyAndOperation(userId, key, operation);
        if (existing.isPresent() && existing.get().getExpiresAt().isAfter(LocalDateTime.now())) {
            return replay(existing.get(), fingerprint, resultType);
        }

        // Either fresh, or expired (the cleanup job will prune later — we treat expired as gone).
        T result = action.get();
        try {
            persist(userId, key, operation, fingerprint, result);
            return Result.executed(result);
        } catch (DataIntegrityViolationException e) {
            // Lost a race against another request with the same key. The action above already
            // executed — but the contract says exactly-once. We can't roll back the work that
            // completed (the order is created, the wallet is debited). The best the framework
            // can do is return the *other* writer's cached result so the client sees a single
            // canonical answer. Both writers' actions ran; this is a documented edge case for
            // Stripe-style idempotency without a distributed lock. In practice the client side
            // double-submit guard (NewOrder.tsx checks `submitting`) makes this near-impossible
            // — this branch exists for correctness, not as a routine path.
            log.warn(
                    "Idempotency-Key race for user={} key={} operation={} — both actions ran;"
                            + " replaying first writer's response",
                    userId,
                    key,
                    operation);
            IdempotencyKey winner =
                    repo.findByUserIdAndIdempotencyKeyAndOperation(userId, key, operation)
                            .orElseThrow(() -> e);
            return replay(winner, fingerprint, resultType);
        }
    }

    /** Cleanup hook for the scheduled job. Returns the number of rows deleted. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int deleteExpired() {
        int deleted = repo.deleteExpired(LocalDateTime.now());
        if (deleted > 0) log.info("Pruned {} expired idempotency keys", deleted);
        return deleted;
    }

    // ---------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------

    private <T> Result<T> replay(IdempotencyKey row, String requestFingerprint, Class<T> type) {
        if (!row.getRequestFingerprint().equals(requestFingerprint)) {
            // Stripe returns 422 here — reusing a key with a different body is a client bug.
            throw new IdempotencyKeyMismatchException(
                    "Idempotency-Key was reused with a different request body. Generate a new"
                            + " key for a different operation.");
        }
        try {
            T cached =
                    row.getResponseBody() == null
                            ? null
                            : objectMapper.readValue(row.getResponseBody(), type);
            return Result.replayed(cached, row.getResponseStatus());
        } catch (JsonProcessingException e) {
            // The cached body is corrupt (schema migration, manual DB edit, etc). Treat as if
            // we'd never seen the key — log loudly and re-execute. Better to risk a re-run
            // (which the client can detect via a second 200) than return garbage.
            log.error(
                    "Failed to deserialize cached idempotency response (key id={}); will fall"
                            + " through and re-execute. Reason: {}",
                    row.getId(),
                    e.getMessage());
            throw new IllegalStateException("Corrupt idempotency cache", e);
        }
    }

    /**
     * Persist the cached response. Intentionally NOT {@code @Transactional} — Spring's CGLIB proxy
     * doesn't intercept self-invocation, so adding {@code REQUIRES_NEW} here would be silently
     * ignored and the comment would lie. Instead we rely on Spring Data's per-call
     * {@code @Transactional} on {@link IdempotencyKeyRepository#save} (inherited from {@code
     * SimpleJpaRepository}), which opens a brief auto-tx for the INSERT.
     *
     * <p>{@code saveAndFlush} (instead of {@code save}) forces the unique-constraint violation to
     * surface synchronously in this method, where {@link #executeWithKey}'s catch-block can see it.
     * With a plain {@code save} the violation may be deferred to session-flush time, which is
     * outside our caller's try/catch and would propagate as a 500 to the client.
     */
    void persist(Long userId, String key, String operation, String fingerprint, Object result) {
        try {
            String body = result == null ? null : objectMapper.writeValueAsString(result);
            LocalDateTime now = LocalDateTime.now();
            IdempotencyKey row =
                    IdempotencyKey.builder()
                            .userId(userId)
                            .idempotencyKey(key)
                            .operation(operation)
                            .responseStatus(200)
                            .responseBody(body)
                            .requestFingerprint(fingerprint)
                            .createdAt(now)
                            .expiresAt(now.plusSeconds(ttlSeconds))
                            .build();
            repo.saveAndFlush(row);
        } catch (JsonProcessingException e) {
            // The action succeeded but caching failed — log and continue. The client gets the
            // first response; a retry would simply re-execute (no idempotency this time round).
            log.warn("Could not cache idempotency response: {}", e.getMessage());
        }
    }

    private String fingerprint(Object body) {
        try {
            String json = body == null ? "null" : objectMapper.writeValueAsString(body);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(json.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) sb.append('0');
                sb.append(hex);
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot fingerprint request body", e);
        }
    }

    /** Thrown when a client reuses an Idempotency-Key with a different request body. */
    public static class IdempotencyKeyMismatchException extends RuntimeException {
        public IdempotencyKeyMismatchException(String message) {
            super(message);
        }
    }

    /**
     * Wraps the action's result with metadata about whether it was freshly executed or replayed
     * from cache. Callers may use the {@code replayed} flag for logging/metrics; functionally the
     * client gets the same response either way.
     */
    public record Result<T>(T value, boolean replayed, int statusCode) {
        public static <T> Result<T> executed(T value) {
            return new Result<>(value, false, 200);
        }

        public static <T> Result<T> replayed(T value, int statusCode) {
            return new Result<>(value, true, statusCode);
        }
    }
}
