package com.smmpanel.service;

import com.smmpanel.dto.binom.AssignedCampaignInfo;
import com.smmpanel.dto.binom.OfferAssignmentRequest;
import com.smmpanel.dto.binom.OfferAssignmentResponse;

import java.util.List;

/**
 * Service interface for managing offer assignments to Binom campaigns
 * Supports Perfect Panel compatibility and Binom V2 integration
 */
public interface OfferAssignmentService {
    
    /**
     * Assigns an offer to all fixed campaigns
     * @param request The offer assignment request containing assignment details
     * @return Response containing assignment details and campaign IDs
     */
    OfferAssignmentResponse assignOfferToFixedCampaigns(OfferAssignmentRequest request);
    
    /**
     * Gets all campaigns assigned to a specific order
     * @param orderId The order ID to get campaigns for
     * @return List of assigned campaign information
     */
    List<AssignedCampaignInfo> getAssignedCampaigns(Long orderId);
    
    /**
     * Gets the current assignment status for an order
     * @param orderId The order ID to check status for
     * @return Assignment status (PENDING, SUCCESS, ERROR, etc.)
     */
    String getAssignmentStatus(Long orderId);
    
    /**
     * Updates the assignment status for an order
     * @param orderId The order ID to update
     * @param status The new status to set
     */
    void updateAssignmentStatus(Long orderId, String status);
    
    /**
     * Validates if the offer assignment request is valid
     * @param request The offer assignment request to validate
     * @return true if the assignment is valid, false otherwise
     */
    boolean validateAssignment(OfferAssignmentRequest request);
}
