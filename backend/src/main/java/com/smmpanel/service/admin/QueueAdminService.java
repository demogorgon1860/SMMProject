package com.smmpanel.service.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smmpanel.dto.admin.QueueStatsDto;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Powers the /admin/system Queues tab.
 *
 * <p>Two data paths:
 *
 * <ol>
 *   <li><b>Primary:</b> RabbitMQ HTTP Management API (port 15672) — gives depth, consumers,
 *       unacked, deliver/ack/publish rates, and dead-letter-exchange wiring.
 *   <li><b>Fallback:</b> Spring AMQP {@link AmqpAdmin#getQueueProperties(String)} which can only
 *       report depth and consumer count for queues we know about by name.
 * </ol>
 *
 * <p>If both paths fail the controller returns 503 so the UI can render an honest "Source
 * unreachable" empty state.
 */
@Slf4j
@Service
public class QueueAdminService {

    /** Hard timeout on each call to the management API. Page must stay responsive. */
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(3);

    /**
     * Names of queues we know exist via {@link com.smmpanel.config.RabbitMQConfig}. Used by the
     * fallback path so we can return *something* even when the management plugin is off.
     */
    private static final List<String> KNOWN_QUEUES =
            List.of("instagram.orders.de", "instagram.results", "instagram.dead");

    private final AmqpAdmin amqpAdmin;
    private final ObjectMapper objectMapper;

    @Value("${app.rabbitmq.management.host:${spring.rabbitmq.host:localhost}}")
    private String mgmtHost;

    @Value("${app.rabbitmq.management.port:15672}")
    private int mgmtPort;

    @Value("${spring.rabbitmq.username:guest}")
    private String mgmtUser;

    @Value("${spring.rabbitmq.password:guest}")
    private String mgmtPass;

    @Value("${spring.rabbitmq.virtual-host:/}")
    private String mgmtVHost;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build();

    public QueueAdminService(AmqpAdmin amqpAdmin, ObjectMapper objectMapper) {
        this.amqpAdmin = amqpAdmin;
        this.objectMapper = objectMapper;
    }

    /** All queues on the configured vhost. Throws on total source unavailability. */
    public List<QueueStatsDto> listQueues() {
        try {
            return listFromManagementApi();
        } catch (Exception e) {
            log.warn(
                    "RabbitMQ management API unavailable ({}), falling back to AmqpAdmin",
                    e.toString());
        }
        // Best-effort fallback — only the queues we declare in code, depth + consumers only.
        List<QueueStatsDto> fallback = listFromAmqpAdmin();
        if (fallback.isEmpty()) {
            throw new IllegalStateException(
                    "RabbitMQ unreachable via both management API and AMQP");
        }
        return fallback;
    }

    /**
     * Purge a queue. Only allowed for queues whose name resolves as a DLQ — guards an admin from
     * accidentally wiping the live work queue with one extra click.
     *
     * @return number of messages purged
     */
    public long purgeQueue(String queueName) {
        if (queueName == null || queueName.isBlank()) {
            throw new IllegalArgumentException("queueName required");
        }
        if (!isLikelyDlq(queueName)) {
            // Belt-and-braces — the controller already filters, but double-checking here keeps the
            // safety guarantee even if a future caller skips the controller path.
            throw new IllegalArgumentException("Refusing to purge non-DLQ queue: " + queueName);
        }
        // purgeQueue(String) returns the message count actually purged; preferred over the
        // (String, boolean) overload which is void.
        return amqpAdmin.purgeQueue(queueName);
    }

    // ---------------- Management API path ----------------

    private List<QueueStatsDto> listFromManagementApi() throws Exception {
        String encodedVHost =
                URLEncoder.encode(mgmtVHost == null ? "/" : mgmtVHost, StandardCharsets.UTF_8);
        String url = "http://" + mgmtHost + ":" + mgmtPort + "/api/queues/" + encodedVHost;
        String basicAuth =
                Base64.getEncoder()
                        .encodeToString(
                                ((mgmtUser == null ? "guest" : mgmtUser)
                                                + ":"
                                                + (mgmtPass == null ? "guest" : mgmtPass))
                                        .getBytes(StandardCharsets.UTF_8));

        HttpRequest req =
                HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(HTTP_TIMEOUT)
                        .header("Authorization", "Basic " + basicAuth)
                        .header("Accept", "application/json")
                        .GET()
                        .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new IllegalStateException("HTTP " + resp.statusCode());
        }
        JsonNode arr = objectMapper.readTree(resp.body());
        if (!arr.isArray()) {
            throw new IllegalStateException("Management API returned non-array body");
        }
        List<QueueStatsDto> out = new ArrayList<>();
        // Index DLX → list of queues for fast lookup of "what's the dead-letter target for X".
        Map<String, JsonNode> queuesByName = new java.util.HashMap<>();
        for (JsonNode q : arr) queuesByName.put(q.path("name").asText(), q);

        for (JsonNode q : arr) {
            String name = q.path("name").asText();
            long depth = q.path("messages").asLong(0);
            int consumers = q.path("consumers").asInt(0);
            long unacked = q.path("messages_unacknowledged").asLong(0);

            JsonNode rates = q.path("message_stats");
            double deliver = readRate(rates.path("deliver_get_details"));
            double ack = readRate(rates.path("ack_details"));
            double publish = readRate(rates.path("publish_details"));

            JsonNode args = q.path("arguments");
            String dlx =
                    args.has("x-dead-letter-exchange")
                            ? args.get("x-dead-letter-exchange").asText()
                            : null;
            long dlqDepth = -1;
            if (dlx != null && !dlx.isBlank()) {
                // Find a queue bound to that DLX. Cheap pass — number of queues is small.
                for (JsonNode candidate : arr) {
                    String cName = candidate.path("name").asText();
                    if (cName == null) continue;
                    if (cName.equalsIgnoreCase(dlx) || cName.startsWith(dlx + ".")) {
                        dlqDepth = candidate.path("messages").asLong(0);
                        break;
                    }
                }
                // Fallback: many setups name the DLQ after the exchange (instagram.dlx →
                // instagram.dead). Spot-check a few common naming conventions.
                if (dlqDepth < 0) {
                    for (String guess : new String[] {dlx + ".queue", dlx + ".dead", "dead"}) {
                        JsonNode hit = queuesByName.get(guess);
                        if (hit != null) {
                            dlqDepth = hit.path("messages").asLong(0);
                            break;
                        }
                    }
                }
            }

            boolean dlqMarker = isLikelyDlq(name);
            out.add(
                    QueueStatsDto.builder()
                            .name(name)
                            .depth(depth)
                            .consumers(consumers)
                            .unacked(unacked)
                            .deliverRate(deliver)
                            .ackRate(ack)
                            .publishRate(publish)
                            .dlqDepth(dlqDepth)
                            .isDlq(dlqMarker)
                            .build());
        }
        // Stable sort: DLQs at the bottom, otherwise alphabetical.
        out.sort(
                (a, b) -> {
                    if (a.isDlq() != b.isDlq()) return a.isDlq() ? 1 : -1;
                    return a.getName().compareToIgnoreCase(b.getName());
                });
        return out;
    }

    /** Pull the RabbitMQ "rate per second" field. Returns -1 if the JSON shape doesn't have it. */
    private static double readRate(JsonNode details) {
        if (details == null || details.isMissingNode() || !details.has("rate")) return -1d;
        return details.path("rate").asDouble(-1d);
    }

    // ---------------- AmqpAdmin fallback ----------------

    private List<QueueStatsDto> listFromAmqpAdmin() {
        List<QueueStatsDto> out = new ArrayList<>();
        for (String name : KNOWN_QUEUES) {
            Properties props;
            try {
                props = amqpAdmin.getQueueProperties(name);
            } catch (Exception e) {
                continue;
            }
            if (props == null) continue;
            out.add(
                    QueueStatsDto.builder()
                            .name(name)
                            .depth(readLong(props, "QUEUE_MESSAGE_COUNT"))
                            .consumers((int) readLong(props, "QUEUE_CONSUMER_COUNT"))
                            .unacked(0L)
                            .deliverRate(-1d)
                            .ackRate(-1d)
                            .publishRate(-1d)
                            .dlqDepth(-1L)
                            .isDlq(isLikelyDlq(name))
                            .build());
        }
        return out;
    }

    private static long readLong(Properties props, String key) {
        if (props == null) return 0L;
        Object v = props.get(key);
        if (v instanceof Number) return ((Number) v).longValue();
        return 0L;
    }

    /** Heuristic — name endswith dlq/dead, contains "dead", or matches our convention. */
    public static boolean isLikelyDlq(String name) {
        if (name == null) return false;
        String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(".dlq")
                || n.endsWith(".dead")
                || n.endsWith("_dlq")
                || n.equals("dead")
                || n.contains(".dead.")
                || n.contains("dead-letter")
                || n.contains("deadletter");
    }

    /** Util: iterate JSON array (used in tests/debug). */
    @SuppressWarnings("unused")
    private static Iterable<JsonNode> iter(JsonNode arr) {
        return () -> {
            Iterator<JsonNode> it = arr.elements();
            return it;
        };
    }
}
