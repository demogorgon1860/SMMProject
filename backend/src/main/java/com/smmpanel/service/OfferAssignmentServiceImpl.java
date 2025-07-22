package com.smmpanel.service;

import com.smmpanel.client.BinomClient;
import com.smmpanel.dto.binom.*;
import com.smmpanel.entity.*;
import com.smmpanel.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Сервис для автоматизации назначения офферов на 3 фиксированные кампании в Binom
 * Упрощенная версия без логики качества и весов
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OfferAssignmentServiceImpl implements OfferAssignmentService {

    private final BinomClient binomClient;
    private final FixedBinomCampaignRepository fixedCampaignRepository;
    private final BinomCampaignRepository campaignRepository;
    private final OrderRepository orderRepository;
    private final OperatorLogRepository operatorLogRepository;
    private final ConversionCoefficientRepository coefficientRepository;

    // Фиксированный коэффициент конверсии (по умолчанию)
    private static final BigDecimal DEFAULT_COEFFICIENT = BigDecimal.valueOf(3.0);

    /**
     * Главный метод для назначения оффера на все 3 фиксированные кампании
     */
    @Override
    @Transactional
    public OfferAssignmentResponse assignOffer(OfferAssignmentRequest request) {
        try {
            log.info("Starting offer assignment for order {} with URL: {}", request.getOrderId(), request.getTargetUrl());

            // 1. Валидация заказа
            Order order = validateOrder(request.getOrderId());

            // 2. Получение всех активных фиксированных кампаний
            List<FixedBinomCampaign> fixedCampaigns = fixedCampaignRepository.findAllActiveCampaigns();
            if (fixedCampaigns.size() != 3) {
                throw new IllegalStateException("Expected exactly 3 active fixed campaigns, found: " + fixedCampaigns.size());
            }

            // 3. Создание или получение оффера в Binom
            String offerId = createOrGetOffer(request);

            // 4. Назначение оффера на все 3 кампании
            List<String> createdCampaignIds = new ArrayList<>();
            for (FixedBinomCampaign fixedCampaign : fixedCampaigns) {
                String campaignId = assignOfferToSingleCampaign(order, fixedCampaign, offerId, request.getTargetUrl());
                createdCampaignIds.add(campaignId);
            }

            // 5. Логирование действия
            logOfferAssignment(order.getId(), request.getOfferName(), offerId, createdCampaignIds);

            // 6. Формирование ответа
            return OfferAssignmentResponse.builder()
                    .offerId(offerId)
                    .offerName(request.getOfferName())
                    .targetUrl(request.getTargetUrl())
                    .orderId(request.getOrderId())
                    .campaignsCreated(createdCampaignIds.size())
                    .campaignIds(createdCampaignIds)
                    .status("SUCCESS")
                    .message("Offer successfully assigned to " + createdCampaignIds.size() + " fixed campaigns")
                    .build();

        } catch (Exception e) {
            log.error("Failed to assign offer for order {}: {}", request.getOrderId(), e.getMessage(), e);
            return OfferAssignmentResponse.builder()
                    .orderId(request.getOrderId())
                    .status("ERROR")
                    .message("Failed to assign offer: " + e.getMessage())
                    .build();
        }
    }

    @Override
    public AssignOfferResponse processOfferAssignment(OfferAssignmentRequest request) {
        // Simplified implementation for async processing
        OfferAssignmentResponse response = assignOffer(request);
        return AssignOfferResponse.builder()
                .campaignId(String.join(",", response.getCampaignIds()))
                .offerId(response.getOfferId())
                .status(response.getStatus())
                .message(response.getMessage())
                .build();
    }

    @Override
    public void updateAssignmentStatus(String assignmentId, String status) {
        // Implementation for updating assignment status
        log.info("Updating assignment {} status to {}", assignmentId, status);
        // Add actual implementation here
    }

    @Override
    public boolean validateAssignment(OfferAssignmentRequest request) {
        // Basic validation
        return request != null && 
               request.getOrderId() != null && 
               request.getTargetUrl() != null && 
               !request.getTargetUrl().isBlank() &&
               request.getOfferName() != null && 
               !request.getOfferName().isBlank();
    }

    /**
     * Валидация существования заказа
     */
    private Order validateOrder(Long orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));
    }

    /**
     * Создание или получение существующего оффера в Binom
     */
    private String createOrGetOffer(OfferAssignmentRequest request) {
        // Проверяем существование оффера
        CheckOfferResponse checkResponse = binomClient.checkOfferExists(request.getOfferName());
        
        if (checkResponse.getExists()) {
            log.info("Offer already exists: {} -> {}", request.getOfferName(), checkResponse.getOfferId());
            return checkResponse.getOfferId();
        }

        // Создаем новый оффер
        CreateOfferRequest createRequest = CreateOfferRequest.builder()
                .name(request.getOfferName())
                .url(request.getTargetUrl())
                .description(request.getDescription())
                .geoTargeting(request.getGeoTargeting() != null ? request.getGeoTargeting() : "US")
                .category("SMM_YOUTUBE")
                .build();

        CreateOfferResponse createResponse = binomClient.createOffer(createRequest);
        log.info("Created new offer: {} -> {}", request.getOfferName(), createResponse.getOfferId());
        
        return createResponse.getOfferId();
    }

    /**
     * Назначение оффера на одну фиксированную кампанию
     */
    private String assignOfferToSingleCampaign(Order order, FixedBinomCampaign fixedCampaign, 
                                             String offerId, String targetUrl) {
        try {
            // Назначаем оффер кампании через Binom API
            AssignOfferResponse assignResponse = binomClient.assignOfferToCampaign(
                    fixedCampaign.getCampaignId(), offerId);

            // Рассчитываем требуемое количество кликов
            int clicksRequired = calculateClicksRequired(order);

            // Создаем запись в binom_campaigns
            BinomCampaign campaign = BinomCampaign.builder()
                    .order(order)
                    .campaignId(fixedCampaign.getCampaignId())
                    .offerId(offerId)
                    .targetUrl(targetUrl)
                    .trafficSource(fixedCampaign.getTrafficSource())
                    .coefficient(DEFAULT_COEFFICIENT)
                    .clicksRequired(clicksRequired)
                    .clicksDelivered(0)
                    .viewsGenerated(0)
                    .status("ACTIVE")
                    .build();

            campaignRepository.save(campaign);

            log.info("Assigned offer {} to fixed campaign {} for order {}", 
                    offerId, fixedCampaign.getCampaignId(), order.getId());

            return fixedCampaign.getCampaignId();

        } catch (Exception e) {
            log.error("Failed to assign offer {} to campaign {}: {}", 
                    offerId, fixedCampaign.getCampaignId(), e.getMessage(), e);
            throw new RuntimeException("Campaign assignment failed: " + e.getMessage(), e);
        }
    }

    /**
     * Расчет требуемого количества кликов (упрощенная формула)
     */
    private int calculateClicksRequired(Order order) {
        // Используем фиксированный коэффициент 3.0
        return (int) (order.getQuantity() * DEFAULT_COEFFICIENT.doubleValue());
    }

    /**
     * Логирование назначения оффера
     */
    private void logOfferAssignment(Long orderId, String offerName, String offerId, List<String> campaignIds) {
        try {
            OperatorLog logEntry = OperatorLog.builder()
                    .operatorId(1L) // Системный пользователь
                    .action("OFFER_ASSIGNMENT")
                    .targetType("ORDER")
                    .targetId(orderId)
                    .details(Map.of(
                            "offer_name", offerName,
                            "offer_id", offerId,
                            "campaigns_assigned", campaignIds,
                            "campaigns_count", campaignIds.size()
                    ))
                    .build();

            operatorLogRepository.save(logEntry);
            log.debug("Logged offer assignment for order {}", orderId);

        } catch (Exception e) {
            log.warn("Failed to log offer assignment: {}", e.getMessage());
        }
    }

    /**
     * Получение информации о назначенных кампаниях для заказа
     */
    public List<AssignedCampaignInfo> getAssignedCampaigns(Long orderId) {
        List<BinomCampaign> campaigns = campaignRepository.findByOrderId(orderId);
        
        return campaigns.stream()
                .map(campaign -> AssignedCampaignInfo.builder()
                        .campaignId(campaign.getCampaignId())
                        .campaignName("Fixed Campaign " + campaign.getTrafficSource().getName())
                        .trafficSourceId(campaign.getTrafficSource().getId())
                        .offerId(campaign.getOfferId())
                        .clicksRequired(campaign.getClicksRequired())
                        .status(campaign.getStatus())
                        .createdAt(campaign.getCreatedAt())
                        .build())
                .toList();
    }
}
