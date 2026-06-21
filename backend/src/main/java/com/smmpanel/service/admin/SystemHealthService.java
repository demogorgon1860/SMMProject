package com.smmpanel.service.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smmpanel.client.InstagramBotClient;
import com.smmpanel.dto.admin.SystemHealthComponent;
import jakarta.annotation.PreDestroy;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.stereotype.Service;

/**
 * Probes every infrastructure component the admin dashboard cares about (Spring Boot, Postgres,
 * Redis, RabbitMQ, Instagram bot, Cryptomus) in parallel under a hard per-check timeout, and
 * returns a list the frontend can render directly.
 *
 * <p>Production contract:
 *
 * <ul>
 *   <li>each individual check is budgeted at {@value #PER_CHECK_TIMEOUT_MS}ms,
 *   <li>checks run on a dedicated bounded thread pool (never on the request thread),
 *   <li>the whole call returns within ~{@value #PER_CHECK_TIMEOUT_MS}ms + small overhead,
 *   <li>a failed/slow check never poisons the rest — its slot is reported as {@code down}.
 * </ul>
 *
 * <p>This replaces the previous {@code AdminService.getSystemHealth()} which called {@code
 * seleniumService.testConnection()} on the request thread and could hang for the full Selenium HTTP
 * timeout, surfacing as a Cloudflare 504 to admins.
 */
@Slf4j
@Service
public class SystemHealthService {

    /** Hard per-check timeout. Whole endpoint must respond within this + small overhead. */
    private static final long PER_CHECK_TIMEOUT_MS = 2_000L;

    /** Above this, status flips from {@code up} to {@code degraded}. */
    private static final long DEGRADED_THRESHOLD_MS = 500L;

    /** Connect/read timeout for outbound HTTP probes (Bot, Cryptomus). Below per-check budget. */
    private static final Duration HTTP_PROBE_TIMEOUT = Duration.ofMillis(1_500);

    /**
     * Soft cache TTL: snapshots older than this trigger a background refresh, but the stale value
     * is still returned to the caller immediately. Combined with {@link #HARD_CACHE_TTL_MS} this
     * gives stale-while-revalidate semantics — the admin dashboard responds in &lt;5 ms after the
     * first warm-up while never showing data older than {@code HARD_CACHE_TTL_MS}.
     */
    private static final long CACHE_TTL_MS = 5_000L;

    /**
     * Hard cache TTL: snapshots older than this are treated as missing and the call blocks for a
     * fresh probe. Backstop for the case where a background refresh stalled — we'd rather make the
     * operator wait 2 s than show them health data from a minute ago.
     */
    private static final long HARD_CACHE_TTL_MS = 30_000L;

    private final DataSource dataSource;
    private final RedisConnectionFactory redisConnectionFactory;
    private final AmqpAdmin amqpAdmin;
    private final InstagramBotClient instagramBotClient;
    private final ObjectMapper objectMapper;

    @Value("${app.cryptomus.api.url:https://api.cryptomus.com/v1}")
    private String cryptomusApiUrl;

    private final ExecutorService executor =
            Executors.newFixedThreadPool(8, daemonThreadFactory("system-health-"));

    private final HttpClient probeHttpClient =
            HttpClient.newBuilder().connectTimeout(HTTP_PROBE_TIMEOUT).build();

    private final AtomicReference<CachedSnapshot> cache = new AtomicReference<>();

    /**
     * Single-flight guard for background refreshes. Prevents the case where N concurrent dashboard
     * tabs all see the cache go stale at the same moment and each kick off their own {@code
     * runAllChecks} run — that would defeat the cache and fan out 6 probes per tab against
     * PG/Redis/RabbitMQ/Bot/Cryptomus.
     */
    private final AtomicBoolean refreshInFlight = new AtomicBoolean(false);

    public SystemHealthService(
            DataSource dataSource,
            RedisConnectionFactory redisConnectionFactory,
            AmqpAdmin amqpAdmin,
            InstagramBotClient instagramBotClient,
            ObjectMapper objectMapper) {
        this.dataSource = dataSource;
        this.redisConnectionFactory = redisConnectionFactory;
        this.amqpAdmin = amqpAdmin;
        this.instagramBotClient = instagramBotClient;
        this.objectMapper = objectMapper;
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    /**
     * Returns the latest snapshot using stale-while-revalidate semantics:
     *
     * <ul>
     *   <li>fresh cache (&lt;{@link #CACHE_TTL_MS}) → return immediately.
     *   <li>stale cache (between {@code CACHE_TTL_MS} and {@link #HARD_CACHE_TTL_MS}) → return the
     *       stale snapshot immediately AND kick off a background refresh.
     *   <li>missing or hard-expired cache → block on a real probe (≤2 s budget).
     * </ul>
     *
     * After the first warm-up this means the endpoint responds in &lt;5 ms regardless of how slow
     * the underlying probes are. Order matches the tile grid the frontend renders.
     */
    public List<SystemHealthComponent> probe() {
        long now = System.currentTimeMillis();
        CachedSnapshot current = cache.get();

        if (current != null) {
            long age = now - current.timestamp();
            if (age < CACHE_TTL_MS) {
                return current.components(); // fast path: serve from cache
            }
            if (age < HARD_CACHE_TTL_MS) {
                triggerBackgroundRefresh();
                return current.components(); // stale-while-revalidate
            }
        }

        // No cache yet, or it's too old to serve. Block on a real probe.
        List<SystemHealthComponent> fresh = runAllChecks();
        cache.compareAndSet(current, new CachedSnapshot(fresh, System.currentTimeMillis()));
        return fresh;
    }

    /**
     * Kick off a single non-blocking refresh. The atomic guard ensures only one is in flight at a
     * time even when many dashboard tabs hit the endpoint simultaneously. Failures are swallowed
     * (logged only) so a transient probe error never poisons the cached snapshot — the next caller
     * will see the previous good values until a refresh succeeds.
     */
    private void triggerBackgroundRefresh() {
        if (!refreshInFlight.compareAndSet(false, true)) {
            return;
        }
        executor.execute(
                () -> {
                    try {
                        List<SystemHealthComponent> fresh = runAllChecks();
                        cache.set(new CachedSnapshot(fresh, System.currentTimeMillis()));
                    } catch (Exception e) {
                        log.warn("Background system-health refresh failed: {}", e.toString());
                    } finally {
                        refreshInFlight.set(false);
                    }
                });
    }

    private List<SystemHealthComponent> runAllChecks() {
        List<NamedCheck> checks =
                List.of(
                        new NamedCheck("Spring Boot", this::checkSpringBoot),
                        new NamedCheck("PostgreSQL", this::checkPostgres),
                        new NamedCheck("Redis", this::checkRedis),
                        new NamedCheck("RabbitMQ", this::checkRabbit),
                        new NamedCheck("Instagram Bot", this::checkInstagramBot),
                        new NamedCheck("Cryptomus", this::checkCryptomus));

        // Submit all checks first so they actually run concurrently.
        List<CompletableFuture<SystemHealthComponent>> futures = new ArrayList<>(checks.size());
        for (NamedCheck c : checks) {
            futures.add(submit(c));
        }

        List<SystemHealthComponent> results = new ArrayList<>(checks.size());
        for (int i = 0; i < checks.size(); i++) {
            results.add(awaitOrDown(futures.get(i), checks.get(i).name()));
        }
        return List.copyOf(results);
    }

    private CompletableFuture<SystemHealthComponent> submit(NamedCheck check) {
        return CompletableFuture.supplyAsync(
                        () -> {
                            try {
                                return check.supplier().get();
                            } catch (Exception e) {
                                log.warn("Health check '{}' threw: {}", check.name(), e.toString());
                                return down(check.name(), shortReason(e));
                            }
                        },
                        executor)
                .orTimeout(PER_CHECK_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    private SystemHealthComponent awaitOrDown(
            CompletableFuture<SystemHealthComponent> f, String name) {
        try {
            // Tiny extra budget over the future's own orTimeout so we never wait longer than
            // the check itself. If orTimeout fires first we get a TimeoutException here.
            return f.get(PER_CHECK_TIMEOUT_MS + 250, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // Best-effort interrupt of the underlying task. orTimeout / get(timeout) only complete
            // the future — they don't touch the worker thread. Without cancel(true), a hung
            // dependency (e.g. unreachable RabbitMQ on a 60s connect timeout) would silently
            // accumulate stuck workers across health-check polls and eventually exhaust the pool.
            f.cancel(true);
            log.warn("Health check '{}' timed out after {}ms", name, PER_CHECK_TIMEOUT_MS);
            return down(name, "timeout");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof java.util.concurrent.TimeoutException) {
                return down(name, "timeout");
            }
            log.warn("Health check '{}' failed: {}", name, cause.toString());
            return down(name, shortReason(cause));
        } catch (InterruptedException e) {
            f.cancel(true);
            Thread.currentThread().interrupt();
            return down(name, "interrupted");
        }
    }

    // ------------------------------------------------------------------
    // Individual checks
    // ------------------------------------------------------------------

    private SystemHealthComponent checkSpringBoot() {
        long uptimeMs = ManagementFactory.getRuntimeMXBean().getUptime();
        Runtime rt = Runtime.getRuntime();
        long usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024L * 1024L);
        long maxMb = rt.maxMemory() / (1024L * 1024L);
        return SystemHealthComponent.builder()
                .name("Spring Boot")
                .status("up")
                .latency(0L)
                .meta(formatUptime(uptimeMs) + " uptime · " + usedMb + "/" + maxMb + " MB heap")
                .build();
    }

    private SystemHealthComponent checkPostgres() {
        long t0 = System.nanoTime();
        try (Connection conn = dataSource.getConnection();
                Statement stmt = conn.createStatement()) {
            // 1s SQL-side timeout so a hung query can't blow our 2s budget.
            stmt.setQueryTimeout(1);
            try (ResultSet rs =
                    stmt.executeQuery(
                            "SELECT (SELECT count(*) FROM pg_stat_activity)::bigint AS conns,"
                                    + " pg_database_size(current_database())::bigint AS size")) {
                long conns = 0L;
                long bytes = 0L;
                if (rs.next()) {
                    conns = rs.getLong(1);
                    bytes = rs.getLong(2);
                }
                long ms = elapsedMs(t0);
                return SystemHealthComponent.builder()
                        .name("PostgreSQL")
                        .status(classify(ms))
                        .latency(ms)
                        .meta(conns + " conn · " + formatGB(bytes) + " GB")
                        .build();
            }
        } catch (Exception e) {
            log.warn("Postgres health probe failed: {}", e.toString());
            return down("PostgreSQL", shortReason(e));
        }
    }

    private SystemHealthComponent checkRedis() {
        long t0 = System.nanoTime();
        try (RedisConnection conn = redisConnectionFactory.getConnection()) {
            conn.ping();
            String memHuman = "lettuce";
            try {
                Properties info = conn.serverCommands().info("memory");
                if (info != null) {
                    String human = info.getProperty("used_memory_human");
                    if (human != null && !human.isBlank()) {
                        memHuman = "lettuce · " + human.trim();
                    }
                }
            } catch (Exception infoEx) {
                // INFO is optional decoration — don't fail the whole check on it.
                log.debug("Redis INFO memory failed: {}", infoEx.toString());
            }
            long ms = elapsedMs(t0);
            return SystemHealthComponent.builder()
                    .name("Redis")
                    .status(classify(ms))
                    .latency(ms)
                    .meta(memHuman)
                    .build();
        } catch (Exception e) {
            log.warn("Redis health probe failed: {}", e.toString());
            return down("Redis", shortReason(e));
        }
    }

    private SystemHealthComponent checkRabbit() {
        long t0 = System.nanoTime();
        try {
            long ordersDepth = queueDepth("instagram.orders.de");
            long dlqDepth = queueDepth("instagram.dead");
            long ms = elapsedMs(t0);
            return SystemHealthComponent.builder()
                    .name("RabbitMQ")
                    .status(classify(ms))
                    .latency(ms)
                    .meta(ordersDepth + " queued · " + dlqDepth + " DLQ")
                    .build();
        } catch (Exception e) {
            log.warn("RabbitMQ health probe failed: {}", e.toString());
            return down("RabbitMQ", shortReason(e));
        }
    }

    /** Returns {@code QUEUE_MESSAGE_COUNT} for the given queue, or {@code 0} if it's missing. */
    private long queueDepth(String queueName) {
        Properties props = amqpAdmin.getQueueProperties(queueName);
        if (props == null) {
            return 0L;
        }
        Object count = props.get("QUEUE_MESSAGE_COUNT");
        if (count instanceof Number) {
            return ((Number) count).longValue();
        }
        return 0L;
    }

    private SystemHealthComponent checkInstagramBot() {
        List<String> instances = instagramBotClient.getBotInstances();
        if (instances == null || instances.isEmpty()) {
            return down("Instagram Bot", "no instances configured");
        }
        // Probe the first instance directly — the existing client wraps the call in a
        // Resilience4j circuit breaker which can return cached "open" failures even when the bot
        // has recovered. For a status panel we want the current truth, not breaker memory.
        String url = instances.get(0) + "/api/health";
        long t0 = System.nanoTime();
        try {
            HttpRequest req =
                    HttpRequest.newBuilder()
                            .uri(URI.create(url))
                            .timeout(HTTP_PROBE_TIMEOUT)
                            .GET()
                            .build();
            HttpResponse<String> resp =
                    probeHttpClient.send(req, HttpResponse.BodyHandlers.ofString());
            long ms = elapsedMs(t0);
            if (resp.statusCode() / 100 != 2) {
                return SystemHealthComponent.builder()
                        .name("Instagram Bot")
                        .status("down")
                        .latency(ms)
                        .meta("HTTP " + resp.statusCode())
                        .build();
            }
            String meta = describeBotBody(resp.body(), instances.size());
            return SystemHealthComponent.builder()
                    .name("Instagram Bot")
                    .status(classify(ms))
                    .latency(ms)
                    .meta(meta)
                    .build();
        } catch (Exception e) {
            log.warn("Instagram bot health probe failed: {}", e.toString());
            return down("Instagram Bot", shortReason(e));
        }
    }

    @SuppressWarnings("unchecked")
    private String describeBotBody(String body, int instanceCount) {
        String workers = null;
        String version = null;
        try {
            Map<String, Object> map = objectMapper.readValue(body, Map.class);
            Object v = map.get("version");
            if (v != null) version = v.toString();
            Object w = map.get("workers");
            if (w == null && map.get("components") instanceof Map<?, ?> comp) {
                Object cw = ((Map<String, Object>) comp).get("workers");
                if (cw != null) w = cw;
            }
            if (w != null) workers = w.toString();
        } catch (Exception parseEx) {
            log.debug("Bot health body unparseable: {}", parseEx.toString());
        }

        StringBuilder meta = new StringBuilder();
        if (instanceCount > 1) {
            meta.append(instanceCount).append(" instances");
        }
        if (workers != null) {
            if (meta.length() > 0) meta.append(" · ");
            meta.append(workers).append(" workers");
        }
        if (version != null) {
            if (meta.length() > 0) meta.append(" · ");
            meta.append("v").append(version);
        }
        if (meta.length() == 0) meta.append("reachable");
        return meta.toString();
    }

    private SystemHealthComponent checkCryptomus() {
        // Hit /payment/info with no auth — Cryptomus answers 4xx fast with a small JSON body.
        // Any HTTP response (even 4xx) proves DNS, TLS, and network egress all work; only a
        // connect/read timeout means the API is actually unreachable.
        String url = stripTrailingSlash(cryptomusApiUrl) + "/payment/info";
        long t0 = System.nanoTime();
        try {
            HttpRequest req =
                    HttpRequest.newBuilder()
                            .uri(URI.create(url))
                            .timeout(HTTP_PROBE_TIMEOUT)
                            .GET()
                            .build();
            HttpResponse<Void> resp =
                    probeHttpClient.send(req, HttpResponse.BodyHandlers.discarding());
            long ms = elapsedMs(t0);
            // 5xx from the API itself signals a problem on their side.
            String status = resp.statusCode() >= 500 ? "degraded" : classify(ms);
            return SystemHealthComponent.builder()
                    .name("Cryptomus")
                    .status(status)
                    .latency(ms)
                    .meta("API reachable")
                    .build();
        } catch (Exception e) {
            log.warn("Cryptomus health probe failed: {}", e.toString());
            return down("Cryptomus", shortReason(e));
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static SystemHealthComponent down(String name, String detail) {
        return SystemHealthComponent.builder()
                .name(name)
                .status("down")
                .latency(null)
                .meta(detail)
                .build();
    }

    private static String classify(long latencyMs) {
        return latencyMs > DEGRADED_THRESHOLD_MS ? "degraded" : "up";
    }

    private static long elapsedMs(long startNanos) {
        return Math.max(0L, (System.nanoTime() - startNanos) / 1_000_000L);
    }

    private static String formatUptime(long uptimeMs) {
        long totalSeconds = uptimeMs / 1000L;
        long days = totalSeconds / 86_400L;
        long hours = (totalSeconds % 86_400L) / 3_600L;
        long minutes = (totalSeconds % 3_600L) / 60L;
        if (days > 0) return days + "d " + hours + "h";
        if (hours > 0) return hours + "h " + minutes + "m";
        return minutes + "m";
    }

    private static String formatGB(long bytes) {
        // Locale.ROOT — server locale could be ru_RU which would emit "10,5" and corrupt the
        // meta string both visually and as a number-bearing JSON field on the wire.
        double gb = bytes / 1_073_741_824d;
        if (gb >= 10) return String.format(Locale.ROOT, "%.1f", gb);
        return String.format(Locale.ROOT, "%.2f", gb);
    }

    private static String stripTrailingSlash(String url) {
        if (url == null || url.isEmpty()) return url;
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /** Short single-line reason suitable for the {@code meta} field on a failed check. */
    private static String shortReason(Throwable t) {
        String msg = t.getMessage();
        if (msg == null || msg.isBlank()) return t.getClass().getSimpleName();
        // Trim ridiculously long stack-derived messages so the UI tile doesn't overflow.
        if (msg.length() > 80) return msg.substring(0, 77) + "...";
        return msg;
    }

    private static ThreadFactory daemonThreadFactory(String namePrefix) {
        AtomicInteger n = new AtomicInteger(1);
        return r -> {
            Thread t = new Thread(r, namePrefix + n.getAndIncrement());
            t.setDaemon(true);
            return t;
        };
    }

    private record NamedCheck(String name, Supplier<SystemHealthComponent> supplier) {}

    private record CachedSnapshot(List<SystemHealthComponent> components, long timestamp) {}
}
