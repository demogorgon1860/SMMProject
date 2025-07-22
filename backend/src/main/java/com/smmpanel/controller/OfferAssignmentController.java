package com.smmpanel.controller;

import com.smmpanel.dto.binom.AssignedCampaignInfo;
import com.smmpanel.dto.binom.OfferAssignmentRequest;
import com.smmpanel.dto.binom.OfferAssignmentResponse;
import com.smmpanel.service.OfferAssignmentService;
import com.smmpanel.service.OfferEventProducer;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST Controller для управления назначением офферов на фиксированные кампании Binom
 */
@Slf4j
@RestController
@RequestMapping("/api/v2/binom/offers")
@RequiredArgsConstructor
@Tag(name = "Binom Offer Assignment", description = "API для назначения офферов на фиксированные кампании")
public class OfferAssignmentController {

    private final OfferAssignmentService offerAssignmentService;
    private final OfferEventProducer offerEventProducer;

    /**
     * Синхронное назначение оффера на все 3 фиксированные кампании
     */
    @PostMapping("/assign")
    @Operation(summary = "Назначить оффер на фиксированные кампании", 
               description = "Создает оффер в Binom и назначает его на все 3 предварительно созданные кампании")
    @PreAuthorize("hasRole('OPERATOR') or hasRole('ADMIN')")
    public ResponseEntity<OfferAssignmentResponse> assignOfferSync(
            @Valid @RequestBody OfferAssignmentRequest request) {
        
        log.info("Received sync offer assignment request for order: {}", request.getOrderId());
        
        try {
            OfferAssignmentResponse response = offerAssignmentService.assignOfferToFixedCampaigns(request);
            
            if ("SUCCESS".equals(response.getStatus())) {
                return ResponseEntity.ok(response);
            } else {
                return ResponseEntity.badRequest().body(response);
            }
            
        } catch (Exception e) {
            log.error("Sync offer assignment failed for order {}: {}", request.getOrderId(), e.getMessage(), e);
            
            OfferAssignmentResponse errorResponse = OfferAssignmentResponse.builder()
                    .orderId(request.getOrderId())
                    .status("ERROR")
                    .message("Internal error: " + e.getMessage())
                    .build();
                    
            return ResponseEntity.internalServerError().body(errorResponse);
        }
    }

    /**
     * Асинхронное назначение оффера через Kafka
     */
    @PostMapping("/assign-async")
    @Operation(summary = "Асинхронное назначение оффера", 
               description = "Отправляет событие в Kafka для назначения оффера на фиксированные кампании")
    @PreAuthorize("hasRole('OPERATOR') or hasRole('ADMIN')")
    public ResponseEntity<String> assignOfferAsync(
            @Valid @RequestBody OfferAssignmentRequest request) {
        
        log.info("Received async offer assignment request for order: {}", request.getOrderId());
        
        try {
            offerEventProducer.sendOfferAssignmentEvent(
                    request.getOrderId(),
                    request.getOfferName(),
                    request.getTargetUrl(),
                    "MANUAL"
            );
            
            return ResponseEntity.ok("Offer assignment event sent successfully for order: " + request.getOrderId());
            
        } catch (Exception e) {
            log.error("Failed to send async offer assignment for order {}: {}", request.getOrderId(), e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body("Failed to send assignment event: " + e.getMessage());
        }
    }

    /**
     * Получение информации о назначенных кампаниях для заказа
     */
    @GetMapping("/order/{orderId}/campaigns")
    @Operation(summary = "Получить назначенные кампании", 
               description = "Возвращает список всех кампаний, назначенных для конкретного заказа")
    @PreAuthorize("hasRole('USER') or hasRole('OPERATOR') or hasRole('ADMIN')")
    public ResponseEntity<List<AssignedCampaignInfo>> getAssignedCampaigns(
            @Parameter(description = "ID заказа") @PathVariable Long orderId) {
        
        try {
            List<AssignedCampaignInfo> campaigns = offerAssignmentService.getAssignedCampaigns(orderId);
            return ResponseEntity.ok(campaigns);
            
        } catch (Exception e) {
            log.error("Failed to get assigned campaigns for order {}: {}", orderId, e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Быстрое назначение для Video Processing Service
     */
    @PostMapping("/quick-assign")
    @Operation(summary = "Быстрое назначение оффера", 
               description = "Упрощенный API для внутренних сервисов (например, Video Processing)")
    @PreAuthorize("hasRole('SYSTEM') or hasRole('ADMIN')")
    public ResponseEntity<String> quickAssignOffer(
            @RequestParam Long orderId,
            @RequestParam String targetUrl) {
        
        try {
            // Генерируем имя оффера
            String offerName = "SMM_Order_" + orderId + "_" + System.currentTimeMillis();
            
            // Отправляем асинхронное событие
            offerEventProducer.sendOfferAssignmentEvent(orderId, offerName, targetUrl, "VIDEO_PROCESSING");
            
            log.info("Quick offer assignment initiated for order {} with URL: {}", orderId, targetUrl);
            return ResponseEntity.ok("Assignment initiated");
            
        } catch (Exception e) {
            log.error("Quick offer assignment failed for order {}: {}", orderId, e.getMessage(), e);
            return ResponseEntity.internalServerError().body("Assignment failed: " + e.getMessage());
        }
    }
