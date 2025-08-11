package com.smmpanel.service;

/**
 * Processing strategy for YouTube orders.
 * Defines different approaches to processing orders with their respective coefficients.
 */
public enum ProcessingStrategy {
    
    /**
     * Clip-based processing strategy.
     * Creates a YouTube clip from the original video for traffic routing.
     * Lower coefficient due to higher conversion rate from clips.
     */
    CLIP_BASED(3.0),
    
    /**
     * Direct traffic processing strategy.
     * Routes traffic directly to the original video without creating clips.
     * Higher coefficient due to lower conversion rate.
     */
    DIRECT_TRAFFIC(4.0);
    
    private final double coefficient;
    
    ProcessingStrategy(double coefficient) {
        this.coefficient = coefficient;
    }
    
    /**
     * Get the coefficient for this processing strategy
     * @return coefficient multiplier for calculating required clicks
     */
    public double getCoefficient() {
        return coefficient;
    }
    
    /**
     * Get processing strategy based on coefficient value
     * @param coefficient the coefficient value
     * @return matching processing strategy or null if no match
     */
    public static ProcessingStrategy fromCoefficient(double coefficient) {
        for (ProcessingStrategy strategy : values()) {
            if (Double.compare(strategy.coefficient, coefficient) == 0) {
                return strategy;
            }
        }
        return null;
    }
}