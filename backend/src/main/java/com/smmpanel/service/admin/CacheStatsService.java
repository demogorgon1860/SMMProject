package com.smmpanel.service.admin;

import com.smmpanel.dto.admin.CacheStatsDto;
import java.util.Properties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisServerCommands;
import org.springframework.stereotype.Service;

/**
 * Powers the /admin/system Cache tab and the cache flush admin action.
 *
 * <p>Reads stats by issuing {@code INFO} sections + {@code DBSIZE} via the Lettuce connection.
 * Always closes the borrowed connection — leaking these would slowly poison the Lettuce pool
 * configured in {@link com.smmpanel.config.RedisConfig}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CacheStatsService {

    private final RedisConnectionFactory redisConnectionFactory;

    /** Single round-trip per section is fine — INFO returns quickly even on busy production. */
    public CacheStatsDto getStats() {
        try (RedisConnection conn = redisConnectionFactory.getConnection()) {
            RedisServerCommands cmds = conn.serverCommands();
            Properties memory = orEmpty(cmds.info("memory"));
            Properties stats = orEmpty(cmds.info("stats"));
            Properties clients = orEmpty(cmds.info("clients"));
            Properties server = orEmpty(cmds.info("server"));
            Long dbSize;
            try {
                dbSize = cmds.dbSize();
            } catch (Exception e) {
                dbSize = -1L;
            }

            long hits = readLong(stats, "keyspace_hits", -1);
            long misses = readLong(stats, "keyspace_misses", -1);
            double hitRate = -1d;
            if (hits >= 0 && misses >= 0 && (hits + misses) > 0) {
                hitRate = 100d * hits / (double) (hits + misses);
            }

            return CacheStatsDto.builder()
                    .usedMemory(readLong(memory, "used_memory", -1))
                    .usedMemoryPeak(readLong(memory, "used_memory_peak", -1))
                    .maxMemory(readLong(memory, "maxmemory", 0))
                    .usedMemoryHuman(memory.getProperty("used_memory_human", "—"))
                    .totalKeys(dbSize == null ? -1 : dbSize)
                    .keyspaceHits(hits)
                    .keyspaceMisses(misses)
                    .hitRate(hitRate)
                    .opsPerSec(readDouble(stats, "instantaneous_ops_per_sec", -1d))
                    .evictedKeys(readLong(stats, "evicted_keys", -1))
                    .expiredKeys(readLong(stats, "expired_keys", -1))
                    .connectedClients((int) readLong(clients, "connected_clients", -1))
                    .uptimeSeconds(readLong(server, "uptime_in_seconds", -1))
                    .version(server.getProperty("redis_version", "?"))
                    .build();
        }
    }

    /**
     * FLUSHDB the active database. Caller must verify the typed confirmation phrase BEFORE invoking
     * this — there is no second guard here.
     *
     * @return number of keys that existed before the flush (best-effort: DBSIZE just before)
     */
    public long flushAll() {
        try (RedisConnection conn = redisConnectionFactory.getConnection()) {
            Long before;
            try {
                before = conn.serverCommands().dbSize();
            } catch (Exception e) {
                before = -1L;
            }
            // FLUSHDB scoped to the active database — never FLUSHALL, which would also wipe other
            // databases that might be sharing the cluster (rate limiting, sessions, etc).
            conn.serverCommands().flushDb();
            return before == null ? -1L : before;
        }
    }

    private static Properties orEmpty(Properties p) {
        return p == null ? new Properties() : p;
    }

    private static long readLong(Properties p, String key, long fallback) {
        String v = p.getProperty(key);
        if (v == null || v.isBlank()) return fallback;
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static double readDouble(Properties p, String key, double fallback) {
        String v = p.getProperty(key);
        if (v == null || v.isBlank()) return fallback;
        try {
            return Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
