package com.smmpanel.service.notification;

import com.smmpanel.dto.notification.TelegramNotificationPayload;
import com.smmpanel.entity.Order;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Slf4j
@Service
@RequiredArgsConstructor
public class TelegramNotificationService {

    @Value("${app.telegram.webhook.url}")
    private String webhookUrl;

    @Value("${app.telegram.webhook.enabled:true}")
    private boolean enabled;

    private final RestTemplate restTemplate;

    @Async("asyncExecutor")
    public void notifyNewOrder(Order order) {
        if (!enabled) {
            return;
        }

        TelegramNotificationPayload payload =
                TelegramNotificationPayload.builder()
                        .orderId(order.getId())
                        .amount(order.getCharge().toString())
                        .service(order.getService().getName())
                        .build();

        sendNotification(payload);
    }

    @Async("asyncExecutor")
    public void notifyOrderCompleted(Order order, Integer completed) {
        if (!enabled) {
            return;
        }

        TelegramNotificationPayload payload =
                TelegramNotificationPayload.builder()
                        .orderId(order.getId())
                        .amount(order.getCharge().toString())
                        .service(order.getService().getName())
                        .status("completed")
                        .completed(completed)
                        .build();

        sendNotification(payload);
    }

    @Async("asyncExecutor")
    public void notifyOrderFailed(Order order, Integer completed) {
        if (!enabled) {
            return;
        }

        TelegramNotificationPayload payload =
                TelegramNotificationPayload.builder()
                        .orderId(order.getId())
                        .amount(order.getCharge().toString())
                        .service(order.getService().getName())
                        .status("failed")
                        .completed(completed)
                        .build();

        sendNotification(payload);
    }

    private void sendNotification(TelegramNotificationPayload payload) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            HttpEntity<TelegramNotificationPayload> request = new HttpEntity<>(payload, headers);

            ResponseEntity<String> response =
                    restTemplate.postForEntity(webhookUrl, request, String.class);

            log.info(
                    "Telegram notification sent: orderId={}, status={}",
                    payload.getOrderId(),
                    response.getStatusCode());
        } catch (Exception e) {
            log.error(
                    "Failed to send Telegram notification for order {}: {}",
                    payload.getOrderId(),
                    e.getMessage());
        }
    }
}
