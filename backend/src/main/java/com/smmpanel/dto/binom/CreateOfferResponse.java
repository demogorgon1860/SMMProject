package com.smmpanel.dto.binom;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response for creating an offer in Binom
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateOfferResponse {
    private String offerId;
    private String name;
    private String url;
    private String status;
    private String geoTargeting;
    private String category;
    
    // Manual builder method since Lombok annotation processing is broken
    public static CreateOfferResponseBuilder builder() {
        return new CreateOfferResponseBuilder();
    }
    
    public static class CreateOfferResponseBuilder {
        private String offerId;
        private String name;
        private String url;
        private String status;
        private String geoTargeting;
        private String category;
        
        public CreateOfferResponseBuilder offerId(String offerId) { this.offerId = offerId; return this; }
        public CreateOfferResponseBuilder name(String name) { this.name = name; return this; }
        public CreateOfferResponseBuilder url(String url) { this.url = url; return this; }
        public CreateOfferResponseBuilder status(String status) { this.status = status; return this; }
        public CreateOfferResponseBuilder geoTargeting(String geoTargeting) { this.geoTargeting = geoTargeting; return this; }
        public CreateOfferResponseBuilder category(String category) { this.category = category; return this; }
        
        public CreateOfferResponse build() {
            CreateOfferResponse response = new CreateOfferResponse();
            response.offerId = this.offerId;
            response.name = this.name;
            response.url = this.url;
            response.status = this.status;
            response.geoTargeting = this.geoTargeting;
            response.category = this.category;
            return response;
        }
    }
}
