package com.smmpanel.monitoring;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.UnsynchronizedAppenderBase;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.StringWriter;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Logback appender that ships every log event to a {@link Sink} (in production: a Redis LIST +
 * pub/sub channel — see {@link RedisLogSink}). The sink is set out-of-band by Spring once Redis is
 * up; events that arrive before that are buffered in a bounded ring queue.
 *
 * <h3>Why this design</h3>
 *
 * <ul>
 *   <li><b>Logback inits before Spring</b>, so we can't @Autowire {@code RedisTemplate} into the
 *       appender. Spring drops the sink in via the static {@link #setSink(Sink)} method during
 *       {@code @PostConstruct} on {@link RedisLogSink}.
 *   <li><b>Logging the logger is forbidden</b> — if the sink fails we MUST NOT call any SLF4J/log4j
 *       method, otherwise the failure would loop back through the appender and re-fail. All
 *       internal errors go through {@link #addError(String)} which writes to Logback's internal
 *       status manager (System.err), bypassing the regular pipeline.
 *   <li><b>Logging the application must never block on Redis.</b> Events are dropped into a bounded
 *       in-memory queue and handed off to a single dedicated drain thread. If Redis is slow or
 *       unreachable, events are dropped (with a counter), never blocking the request thread.
 * </ul>
 *
 * <p>The appender is wired in {@code logback-spring.xml}.
 */
public class LogbackRedisAppender extends UnsynchronizedAppenderBase<ILoggingEvent> {

    /**
     * Strategy interface implemented by Spring once the Redis client is up. Keeping it as a {@link
     * Consumer} of pre-serialized JSON strings means the appender has zero compile-time coupling to
     * Spring or Spring Data Redis — both of which are unavailable when Logback boots.
     */
    @FunctionalInterface
    public interface Sink extends Consumer<String> {}

    /** Bound on the in-memory hand-off queue. ~5k events at typical 200B = 1MB. */
    private static final int QUEUE_CAPACITY = 5_000;

    /** Field length cap — keeps a single rogue stack trace from blowing up Redis memory. */
    private static final int MAX_MESSAGE_BYTES = 8_192;

    private static final int MAX_THROWABLE_BYTES = 16_384;

    /** Produces clean stable JSON. Uses the JSON ISO-8601 form, never timestamps. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Single global sink — only one of these in the JVM at a time. */
    private static volatile Sink sink;

    private final BlockingQueue<ILoggingEvent> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final AtomicLong dropped = new AtomicLong();
    private long lastDropReportNanos = 0L;

    private Thread drainThread;

    /**
     * Called by {@link RedisLogSink} once Spring has constructed the Redis client. Replaces any
     * previous sink (no-op if same).
     */
    public static void setSink(Sink newSink) {
        sink = newSink;
    }

    /** Called on shutdown so we stop trying to push to a torn-down Redis bean. */
    public static void clearSink(Sink expected) {
        // CAS-ish: only clear if the current sink is the one we set, so concurrent reconfigure is
        // safe. We don't have a real CAS for volatile refs, but this is good enough — sink
        // set/clear
        // happens once per app lifetime.
        if (sink == expected) {
            sink = null;
        }
    }

    @Override
    public void start() {
        if (isStarted()) return;
        super.start();
        drainThread =
                new Thread(this::drainLoop, "logback-redis-drain-" + System.identityHashCode(this));
        drainThread.setDaemon(true);
        drainThread.start();
    }

    @Override
    public void stop() {
        if (!isStarted()) return;
        super.stop();
        Thread t = drainThread;
        if (t != null) {
            t.interrupt();
            try {
                t.join(500);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
        drainThread = null;
        queue.clear();
    }

    @Override
    protected void append(ILoggingEvent event) {
        if (event == null) return;
        // Pre-load any deferred fields *before* the request thread leaves — calling
        // getCallerData() on the drain thread crashes inside Logback because the originating
        // call frame is gone. Don't touch caller data here either; we don't need it.
        event.prepareForDeferredProcessing();
        if (!queue.offer(event)) {
            // Drop with a counter. We can't block the caller; if the drain is behind that's a
            // signal Redis is unhealthy and we mustn't make things worse.
            long total = dropped.incrementAndGet();
            long now = System.nanoTime();
            if (now - lastDropReportNanos > TimeUnit.SECONDS.toNanos(60)) {
                lastDropReportNanos = now;
                addError(
                        "LogbackRedisAppender queue full — dropped "
                                + total
                                + " events since startup");
            }
        }
    }

    private void drainLoop() {
        while (isStarted()) {
            ILoggingEvent event;
            try {
                event = queue.poll(1, TimeUnit.SECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
            if (event == null) continue;

            Sink target = sink;
            if (target == null) {
                // Spring isn't ready — drop quietly. Buffering forever would just OOM.
                continue;
            }
            String json;
            try {
                json = serialize(event);
            } catch (Exception e) {
                // Bad JSON → drop this event, never fail the loop.
                addError("Failed to serialize log event: " + e.getMessage());
                continue;
            }
            try {
                target.accept(json);
            } catch (Exception e) {
                // MUST swallow — never log via SLF4J from inside the appender.
                addError("Sink failed to accept event: " + e.getMessage());
            }
        }
    }

    private String serialize(ILoggingEvent event) throws Exception {
        StringWriter sw = new StringWriter(256);
        try (JsonGenerator g = MAPPER.getFactory().createGenerator(sw)) {
            g.writeStartObject();
            g.writeStringField("ts", Instant.ofEpochMilli(event.getTimeStamp()).toString());
            g.writeStringField("level", event.getLevel().toString());
            g.writeStringField("source", shortLogger(event.getLoggerName()));
            g.writeStringField("logger", event.getLoggerName());
            g.writeStringField("thread", event.getThreadName());
            g.writeStringField("msg", trim(event.getFormattedMessage(), MAX_MESSAGE_BYTES));

            IThrowableProxy tp = event.getThrowableProxy();
            if (tp != null) {
                g.writeStringField(
                        "throwable", trim(ThrowableProxyUtil.asString(tp), MAX_THROWABLE_BYTES));
                g.writeStringField("throwableClass", tp.getClassName());
            }

            Map<String, String> mdc = event.getMDCPropertyMap();
            if (mdc != null && !mdc.isEmpty()) {
                ObjectNode mdcNode = MAPPER.createObjectNode();
                for (Map.Entry<String, String> e : mdc.entrySet()) {
                    String k = e.getKey();
                    if (k == null) continue;
                    // Filter likely-sensitive MDC keys at the source. Don't expose secrets via the
                    // admin Logs panel even if some other code path puts them there.
                    if (isSensitive(k)) continue;
                    mdcNode.put(k, e.getValue());
                }
                if (mdcNode.size() > 0) {
                    g.writeFieldName("mdc");
                    MAPPER.writeTree(g, mdcNode);
                }
            }
            g.writeEndObject();
        }
        return sw.toString();
    }

    /** Strip the package — the source field is shown as the second column on the UI. */
    private static String shortLogger(String full) {
        if (full == null) return "?";
        int dot = full.lastIndexOf('.');
        return dot < 0 ? full : full.substring(dot + 1);
    }

    private static String trim(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    /**
     * Best-effort. Cheap. Conservative — false negatives are fine, false positives mean opacity.
     */
    private static boolean isSensitive(String key) {
        String k = key.toLowerCase();
        return k.contains("password")
                || k.contains("secret")
                || k.contains("token")
                || k.contains("apikey")
                || k.contains("api_key")
                || k.contains("authorization")
                || k.contains("cookie")
                || k.contains("credential");
    }
}
