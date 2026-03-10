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
 * <p>Orders are routed to the DE queue based on service geo_targeting: - DE (German) →
 * instagram.orders.de queue → Bot server - ENG (USA/Europe) → instagram.orders.de queue → same Bot
 * server
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
        return InstagramOrderMessage.builder()
                .externalId(String.valueOf(order.getId()))
                .type(mapServiceCategoryToType(service.getCategory()))
                .targetUrl(order.getLink())
                .count(order.getQuantity())
                .commentText(order.getCustomComments())
                // No callbackUrl - results come back via instagram.results queue
                .priority(order.getProcessingPriority() != null ? order.getProcessingPriority() : 0)
                .geoTargeting(geoTargeting) // Bot uses this for gender/geo profile group selection
                .build();
    }

    /**
     * Resolves the geo targeting string from the service.
     * Returns "DE" or "ENG" (original values preserved for bot profile selection).
     */
    private String resolveGeoTargeting(Service service) {
        String geo = service.getGeoTargeting();
        if (geo == null || geo.isBlank()) {
            return "DE";
        }
        return geo.toUpperCase();
    }

    /** Determines routing key from service geo_targeting. ENG routes to same DE bot server. */
    private String determineGeoTargeting(Service service) {
        String geo = resolveGeoTargeting(service);
        // ENG (USA/Europe) uses the same bot server as DE
        if (geo.equalsIgnoreCase("ENG")) {
            return "DE";
        }
        return geo;
    }

    /** Maps service category to Instagram bot order type. */
    private String mapServiceCategoryToType(String category) {
        if (category == null) {
            return "like";
        }

        return switch (category.toUpperCase()) {
            case "INSTAGRAM_LIKES" -> "like";
            case "INSTAGRAM_FOLLOWERS" -> "follow";
            case "INSTAGRAM_COMMENTS" -> "comment";
            case "INSTAGRAM_LIKES_FOLLOWERS" -> "like_follow";
            case "INSTAGRAM_LIKES_COMMENTS" -> "like_comment";
            case "INSTAGRAM_LIKES_COMMENTS_FOLLOWERS" -> "like_comment_follow";
            default -> "like";
        };
    }
}
