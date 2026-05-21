package com.smmpanel.service.instagram;

import com.smmpanel.config.RabbitMQConfig;
import com.smmpanel.dto.instagram.InstagramOrderMessage;
import com.smmpanel.entity.Order;
import com.smmpanel.entity.Service;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes Instagram orders to RabbitMQ for geo-based routing.
 *
 * <p>Routing matrix (service.geo_targeting → routing key → queue):
 *
 * <ul>
 *   <li>DE → DE → instagram.orders.de
 *   <li>ENG → DE → instagram.orders.de (shared with German pool)
 *   <li>MIX_GEO → MIX_GEO → instagram.orders.mix_geo (cheap non-warmed pool)
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InstagramRabbitPublisher {

    private final RabbitTemplate rabbitTemplate;

    // Callback URL not needed - results come back via instagram.results queue

    /**
     * Publishes an Instagram order to the appropriate geo-specific queue.
     *
     * @param order The panel order to process
     * @param service The service containing geo_targeting info
     * @return true if published successfully
     */
    public boolean publishOrder(Order order, Service service) {
        try {
            String geoTargeting = determineGeoTargeting(service);
            String routingKey = geoTargeting.toUpperCase();

            InstagramOrderMessage message = buildOrderMessage(order, service);

            rabbitTemplate.convertAndSend(RabbitMQConfig.INSTAGRAM_EXCHANGE, routingKey, message);

            log.info(
                    "Published Instagram order to RabbitMQ: orderId={}, type={}, geo={},"
                            + " queue=instagram.orders.{}",
                    order.getId(),
                    message.getType(),
                    geoTargeting,
                    routingKey.toLowerCase());

            return true;

        } catch (Exception e) {
            log.error(
                    "Failed to publish Instagram order to RabbitMQ: orderId={}, error={}",
                    order.getId(),
                    e.getMessage(),
                    e);
            return false;
        }
    }

    /** Builds the order message from panel order and service. */
    private InstagramOrderMessage buildOrderMessage(Order order, Service service) {
        String geoTargeting = resolveGeoTargeting(service);
        String gender = parseGenderFromServiceName(service.getName());
        String commentText = buildCommentTextWithGender(order.getCustomComments(), gender);
        return InstagramOrderMessage.builder()
                .externalId(String.valueOf(order.getId()))
                .type(mapServiceCategoryToType(service.getCategory()))
                .targetUrl(order.getLink())
                .count(order.getQuantity())
                .commentText(commentText)
                // No callbackUrl - results come back via instagram.results queue
                .priority(order.getProcessingPriority() != null ? order.getProcessingPriority() : 0)
                .geoTargeting(geoTargeting) // Bot uses this for geo profile group selection
                .minActionDelaySeconds(service.getMinActionDelaySeconds())
                .maxActionDelaySeconds(service.getMaxActionDelaySeconds())
                .build();
    }

    /**
     * Parse the gender targeting marker from the service name. Service names follow the
     * pattern {@code "Instagram <Action> [<Gender>] [<Geo>]"} where {@code <Gender>} is one of
     * {@code Mix Gender}, {@code Male}, {@code MALE}, {@code Female}, {@code FEMALE}. Returns
     * {@code null} when no gender marker is present (Mix Gender or non-gendered service) — the
     * caller treats null as "no gender preference" and leaves commentText untouched, which
     * lets the bot fall back to its default mixed-pool distribution.
     *
     * <p>Order of checks matters: "FEMALE" must be tested first, otherwise the substring
     * check for "MALE" would match inside "FEMALE" and route female services to the male
     * pool. Case-insensitive on the full service name.
     */
    public static String parseGenderFromServiceName(String serviceName) {
        if (serviceName == null) return null;
        String upper = serviceName.toUpperCase();
        if (upper.contains("[FEMALE]")) return "FEMALE";
        if (upper.contains("[MALE]")) return "MALE";
        return null;
    }

    /**
     * Encode the gender preference into commentText using the {@code GENDER:MALE\n} /
     * {@code GENDER:FEMALE\n} prefix that the Instagram bot's {@code parseCommentText}
     * recognises. The bot strips the prefix before treating the rest of the field as the
     * actual comment payload, so:
     *
     * <ul>
     *   <li>Custom Comments services: user-provided text is preserved verbatim after the
     *       prefix.
     *   <li>Likes / Followers (no commentText): the message is just the prefix line; the
     *       bot uses the gender for profile-pool selection and ignores the empty payload
     *       since the action type doesn't need text.
     *   <li>Mix Gender services: no prefix is added so the bot keeps its default mixed
     *       distribution.
     * </ul>
     *
     * <p>Without this the bot received {@code gender=} (empty) on every order — verified
     * by customer report on order 15260 (Custom Comments [MALE]) where female accounts
     * posted the comments because the bot defaulted to the mixed pool.
     */
    public static String buildCommentTextWithGender(String customComments, String gender) {
        if (gender == null) {
            return customComments;
        }
        // Defensive: if some upstream path already injected a prefix, don't stack another.
        if (customComments != null
                && (customComments.startsWith("GENDER:MALE\n")
                        || customComments.startsWith("GENDER:FEMALE\n"))) {
            return customComments;
        }
        String prefix = "GENDER:" + gender + "\n";
        if (customComments == null || customComments.isEmpty()) {
            return prefix;
        }
        return prefix + customComments;
    }

    /**
     * Resolves the geo targeting string from the service. Returns "DE" or "ENG" (original values
     * preserved for bot profile selection).
     */
    private String resolveGeoTargeting(Service service) {
        String geo = service.getGeoTargeting();
        if (geo == null || geo.isBlank()) {
            return "DE";
        }
        return geo.toUpperCase();
    }

    /**
     * Determines routing key from service geo_targeting. ENG routes to the same DE bot server;
     * MIX_GEO has its own dedicated queue.
     */
    private String determineGeoTargeting(Service service) {
        String geo = resolveGeoTargeting(service);
        // ENG (USA/Europe) uses the same bot server as DE
        if (geo.equalsIgnoreCase("ENG")) {
            return "DE";
        }
        // MIX_GEO has its own queue served by the cheap non-warmed account pool
        if (geo.equalsIgnoreCase("MIX_GEO")) {
            return "MIX_GEO";
        }
        return geo;
    }

    /** Maps service category to Instagram bot order type. */
    private String mapServiceCategoryToType(String category) {
        if (category == null) {
            return "like";
        }

        return switch (category.toUpperCase()) {
            case "INSTAGRAM_LIKES", "INSTAGRAM_MIX_GEO_LIKES" -> "like";
            case "INSTAGRAM_FOLLOWERS", "INSTAGRAM_MIX_GEO_FOLLOWERS" -> "follow";
            case "INSTAGRAM_COMMENTS" -> "comment";
            case "INSTAGRAM_LIKES_FOLLOWERS" -> "like_follow";
            case "INSTAGRAM_LIKES_COMMENTS" -> "like_comment";
            case "INSTAGRAM_LIKES_COMMENTS_FOLLOWERS" -> "like_comment_follow";
            default -> "like";
        };
    }
}
