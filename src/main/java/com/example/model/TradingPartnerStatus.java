package com.example.model;

import java.time.LocalDateTime;

/**
 * Trading partner status from database.
 * Used for pre-validation before processing events.
 */
public record TradingPartnerStatus(
    String partnerId,
    String partnerName,
    String status,  // ACTIVE, INACTIVE, SUSPENDED
    LocalDateTime updatedAt
) {
    public boolean isActive() {
        return "ACTIVE".equalsIgnoreCase(status);
    }
    
    public boolean isInactive() {
        return "INACTIVE".equalsIgnoreCase(status);
    }
    
    public boolean isSuspended() {
        return "SUSPENDED".equalsIgnoreCase(status);
    }
}
