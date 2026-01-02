package com.example.service;

import com.example.model.BusinessUnitStatus;
import com.example.model.OrderEvent;
import com.example.model.TradingPartnerStatus;
import com.example.repository.OrderRepository;
import com.github.benmanes.caffeine.cache.Cache;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Service for validating trading partner and business unit status.
 * 
 * CACHING STRATEGY:
 * - Both partner and unit status are cached for 10 minutes
 * - Cache-aside pattern: check cache first, load from DB on miss
 * - Reduces DB calls for repeated events from same partner/unit
 * 
 * VALIDATION RULES:
 * - If BOTH partner AND unit are INACTIVE → skip event
 * - If either is ACTIVE → process event
 * - If partner/unit not found → treat as inactive (configurable)
 */
@Service
@Slf4j
public class PartnerValidationService {

    private final OrderRepository orderRepository;
    private final Cache<String, TradingPartnerStatus> tradingPartnerCache;
    private final Cache<String, BusinessUnitStatus> businessUnitCache;
    
    // Metrics
    private final Timer partnerLookupTimer;
    private final Timer unitLookupTimer;
    private final Counter partnerCacheHits;
    private final Counter partnerCacheMisses;
    private final Counter unitCacheHits;
    private final Counter unitCacheMisses;
    private final Counter eventsSkipped;

    public PartnerValidationService(
            OrderRepository orderRepository,
            Cache<String, TradingPartnerStatus> tradingPartnerCache,
            Cache<String, BusinessUnitStatus> businessUnitCache,
            MeterRegistry meterRegistry) {
        this.orderRepository = orderRepository;
        this.tradingPartnerCache = tradingPartnerCache;
        this.businessUnitCache = businessUnitCache;
        
        // Initialize metrics
        this.partnerLookupTimer = Timer.builder("partner.lookup.time")
                .description("Time to lookup trading partner status")
                .register(meterRegistry);
        this.unitLookupTimer = Timer.builder("business.unit.lookup.time")
                .description("Time to lookup business unit status")
                .register(meterRegistry);
        this.partnerCacheHits = Counter.builder("partner.cache.hits")
                .description("Trading partner cache hits")
                .register(meterRegistry);
        this.partnerCacheMisses = Counter.builder("partner.cache.misses")
                .description("Trading partner cache misses")
                .register(meterRegistry);
        this.unitCacheHits = Counter.builder("business.unit.cache.hits")
                .description("Business unit cache hits")
                .register(meterRegistry);
        this.unitCacheMisses = Counter.builder("business.unit.cache.misses")
                .description("Business unit cache misses")
                .register(meterRegistry);
        this.eventsSkipped = Counter.builder("events.skipped.inactive")
                .description("Events skipped due to inactive partner/unit")
                .register(meterRegistry);
    }

    /**
     * Validation result containing partner and unit status.
     */
    public record ValidationResult(
        boolean shouldProcess,
        Optional<TradingPartnerStatus> partnerStatus,
        Optional<BusinessUnitStatus> unitStatus,
        String skipReason
    ) {
        public static ValidationResult process(
                TradingPartnerStatus partner, 
                BusinessUnitStatus unit) {
            return new ValidationResult(true, Optional.ofNullable(partner), 
                    Optional.ofNullable(unit), null);
        }
        
        public static ValidationResult skip(
                TradingPartnerStatus partner, 
                BusinessUnitStatus unit, 
                String reason) {
            return new ValidationResult(false, Optional.ofNullable(partner), 
                    Optional.ofNullable(unit), reason);
        }
    }

    /**
     * Validate if an event should be processed based on partner/unit status.
     * 
     * @param event The order event to validate
     * @return ValidationResult with shouldProcess flag and status details
     */
    public ValidationResult validateEvent(OrderEvent event) {
        long startTime = System.currentTimeMillis();
        
        String partnerName = event.tradingPartnerName();
        String unitName = event.businessUnitName();
        
        log.debug("Validating event - partner: {}, unit: {}", partnerName, unitName);
        
        // Fetch statuses (with caching)
        TradingPartnerStatus partnerStatus = getTradingPartnerStatus(partnerName);
        BusinessUnitStatus unitStatus = getBusinessUnitStatus(unitName);
        
        // Validation logic: skip only if BOTH are inactive
        boolean partnerInactive = (partnerStatus == null || partnerStatus.isInactive());
        boolean unitInactive = (unitStatus == null || unitStatus.isInactive());
        
        if (partnerInactive && unitInactive) {
            eventsSkipped.increment();
            String reason = buildSkipReason(partnerStatus, unitStatus, partnerName, unitName);
            log.warn("Skipping event - {}", reason);
            return ValidationResult.skip(partnerStatus, unitStatus, reason);
        }
        
        long elapsed = System.currentTimeMillis() - startTime;
        log.debug("Event validation passed in {}ms - partner: {} ({}), unit: {} ({})",
                elapsed,
                partnerName, partnerStatus != null ? partnerStatus.status() : "NOT_FOUND",
                unitName, unitStatus != null ? unitStatus.status() : "NOT_FOUND");
        
        return ValidationResult.process(partnerStatus, unitStatus);
    }

    /**
     * Get trading partner status with caching.
     */
    private TradingPartnerStatus getTradingPartnerStatus(String partnerName) {
        // Check cache first
        TradingPartnerStatus cached = tradingPartnerCache.getIfPresent(partnerName);
        if (cached != null) {
            partnerCacheHits.increment();
            log.debug("Trading partner cache HIT: {}", partnerName);
            return cached;
        }
        
        partnerCacheMisses.increment();
        log.debug("Trading partner cache MISS: {}", partnerName);
        
        // Load from DB
        long start = System.nanoTime();
        Optional<TradingPartnerStatus> fromDb = orderRepository.findTradingPartnerByName(partnerName);
        partnerLookupTimer.record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        
        // Cache the result (even if not found - cache null to avoid repeated lookups)
        fromDb.ifPresent(status -> tradingPartnerCache.put(partnerName, status));
        
        return fromDb.orElse(null);
    }

    /**
     * Get business unit status with caching.
     */
    private BusinessUnitStatus getBusinessUnitStatus(String unitName) {
        // Check cache first
        BusinessUnitStatus cached = businessUnitCache.getIfPresent(unitName);
        if (cached != null) {
            unitCacheHits.increment();
            log.debug("Business unit cache HIT: {}", unitName);
            return cached;
        }
        
        unitCacheMisses.increment();
        log.debug("Business unit cache MISS: {}", unitName);
        
        // Load from DB
        long start = System.nanoTime();
        Optional<BusinessUnitStatus> fromDb = orderRepository.findBusinessUnitByName(unitName);
        unitLookupTimer.record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        
        // Cache the result
        fromDb.ifPresent(status -> businessUnitCache.put(unitName, status));
        
        return fromDb.orElse(null);
    }

    private String buildSkipReason(
            TradingPartnerStatus partner, 
            BusinessUnitStatus unit,
            String partnerName,
            String unitName) {
        StringBuilder reason = new StringBuilder("BOTH INACTIVE - ");
        
        if (partner == null) {
            reason.append("partner '").append(partnerName).append("' NOT_FOUND");
        } else {
            reason.append("partner '").append(partnerName).append("' ").append(partner.status());
        }
        
        reason.append(", ");
        
        if (unit == null) {
            reason.append("unit '").append(unitName).append("' NOT_FOUND");
        } else {
            reason.append("unit '").append(unitName).append("' ").append(unit.status());
        }
        
        return reason.toString();
    }

    /**
     * Invalidate cache entries (useful for testing or admin operations).
     */
    public void invalidatePartnerCache(String partnerName) {
        tradingPartnerCache.invalidate(partnerName);
        log.info("Invalidated trading partner cache: {}", partnerName);
    }

    public void invalidateUnitCache(String unitName) {
        businessUnitCache.invalidate(unitName);
        log.info("Invalidated business unit cache: {}", unitName);
    }

    public void invalidateAllCaches() {
        tradingPartnerCache.invalidateAll();
        businessUnitCache.invalidateAll();
        log.info("Invalidated all partner/unit caches");
    }
}
