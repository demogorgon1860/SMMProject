package com.smmpanel.dto.admin;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Snapshot of Redis state for the {@code /admin/system} Cache tab. Sourced from {@code INFO
 * memory}, {@code INFO stats}, {@code INFO clients}, {@code INFO server}, and {@code DBSIZE}.
 *
 * <p>Numbers that are unavailable (older Redis, ACL restrictions, partial response) are returned as
 * {@code -1} so the frontend can render "—" instead of misleading zeros.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CacheStatsDto {

    /** Bytes used by Redis (includes overhead). */
    private long usedMemory;

    /** Peak memory observed since startup. */
    private long usedMemoryPeak;

    /** Hard limit configured via {@code maxmemory}. 0 means uncapped. */
    private long maxMemory;

    /** Human-readable form of {@link #usedMemory} (e.g. "412.40M"). */
    private String usedMemoryHuman;

    /** Total keys across all selected databases (DBSIZE on the active db). */
    private long totalKeys;

    /** keyspace_hits cumulative since startup. */
    private long keyspaceHits;

    /** keyspace_misses cumulative since startup. */
    private long keyspaceMisses;

    /**
     * Hit rate as a percentage (0..100). -1 if hits+misses == 0 (no traffic yet, ratio undefined).
     */
    private double hitRate;

    /** Smoothed instantaneous ops/sec from Redis itself. */
    private double opsPerSec;

    /** Cumulative evicted keys (max-memory eviction). */
    private long evictedKeys;

    /** Cumulative expired keys (TTL). */
    private long expiredKeys;

    /** Currently connected clients. */
    private int connectedClients;

    /** Uptime in seconds. */
    private long uptimeSeconds;

    /** Redis server version string (e.g. "7.2.4"). */
    private String version;
}
