package com.smmpanel.service;

import com.smmpanel.entity.Order;
import com.smmpanel.entity.VideoProcessing;
import com.smmpanel.repository.VideoProcessingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Обновленный VideoProcessingService с интеграцией назначения офферов
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FixedVideoProcessingService {

    private final VideoProcessingRepository videoProcessingRepository;
    @Qualifier("kafkaOfferEventProducer")
    private final OfferEventProducer offerEventProducer;

    /**
     * Завершение обработки видео с автоматическим назначением оффера
     */
    @Transactional
    public void completeVideoProcessing(Long orderId, String finalUrl, boolean clipCreated) {
        try {
            VideoProcessing processing = videoProcessingRepository.findByOrderId(orderId)
                    .orElseThrow(() -> new IllegalArgumentException("Video processing not found for order: " + orderId));

            // Обновляем статус обработки
            processing.setProcessingStatus("COMPLETED");
            processing.setClipCreated(clipCreated);
            
            if (clipCreated) {
                processing.setClipUrl(finalUrl);
            }
            
            videoProcessingRepository.save(processing);

            // Автоматически назначаем оффер на фиксированные кампании
            assignOfferAutomatically(orderId, finalUrl, clipCreated);

            log.info("Completed video processing for order {} with final URL: {}", orderId, finalUrl);

        } catch (Exception e) {
            log.error("Failed to complete video processing for order {}: {}", orderId, e.getMessage(), e);
            throw e;
        }
    }

    /**
     * Автоматическое назначение оффера после завершения обработки видео
     */
    private void assignOfferAutomatically(Long orderId, String targetUrl, boolean clipCreated) {
        try {
            String offerName = generateOfferName(orderId, clipCreated);
            String source = clipCreated ? "VIDEO_PROCESSING_CLIP" : "VIDEO_PROCESSING_ORIGINAL";

            // Отправляем событие для асинхронного назначения оффера
            offerEventProducer.sendOfferAssignmentEvent(orderId, offerName, targetUrl, source);

            log.info("Initiated automatic offer assignment for order {} (clip: {})", orderId, clipCreated);

        } catch (Exception e) {
            log.error("Failed to initiate automatic offer assignment for order {}: {}", orderId, e.getMessage(), e);
            // Не прерываем основной процесс, только логируем ошибку
        }
    }

    /**
     * Генерация имени оффера
     */
    private String generateOfferName(Long orderId, boolean clipCreated) {
        String type = clipCreated ? "CLIP" : "ORIGINAL";
        return String.format("SMM_%s_Order_%d_%d", type, orderId, System.currentTimeMillis());
    }

    /**
     * Обработка ошибки видео
     */
    @Transactional
    public void handleVideoProcessingError(Long orderId, String errorMessage) {
        try {
            VideoProcessing processing = videoProcessingRepository.findByOrderId(orderId)
                    .orElseThrow(() -> new IllegalArgumentException("Video processing not found for order: " + orderId));

            processing.setProcessingStatus("ERROR");
            processing.setErrorMessage(errorMessage);
            processing.setProcessingAttempts(processing.getProcessingAttempts() + 1);

            videoProcessingRepository.save(processing);

            log.warn("Video processing error for order {}: {}", orderId, errorMessage);

        } catch (Exception e) {
            log.error("Failed to handle video processing error for order {}: {}", orderId, e.getMessage(), e);
        }
    }
}
