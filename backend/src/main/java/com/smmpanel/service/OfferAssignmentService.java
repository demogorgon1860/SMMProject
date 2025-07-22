package com.smmpanel.service;

import com.smmpanel.dto.binom.AssignOfferResponse;
import com.smmpanel.dto.binom.OfferAssignmentRequest;
import com.smmpanel.dto.binom.OfferAssignmentResponse;

public interface OfferAssignmentService {
    
    /**
     * Assigns an offer to a campaign
     * @param request The offer assignment request containing assignment details
     * @return Response containing assignment details
     */
    OfferAssignmentResponse assignOffer(OfferAssignmentRequest request);
    
    /**
     * Processes the offer assignment asynchronously
     * @param request The offer assignment request
     * @return Response with initial assignment status
     */
    AssignOfferResponse processOfferAssignment(OfferAssignmentRequest request);
    
    /**
     * Updates the status of an offer assignment
     * @param assignmentId The ID of the assignment to update
     * @param status The new status to set
     */
    void updateAssignmentStatus(String assignmentId, String status);
    
    /**
     * Validates if the offer can be assigned
     * @param request The offer assignment request to validate
     * @return true if the assignment is valid, false otherwise
     */
    boolean validateAssignment(OfferAssignmentRequest request);
}
