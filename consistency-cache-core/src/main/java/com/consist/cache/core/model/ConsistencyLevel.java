package com.consist.cache.core.model;

/**
 * Cache consistency level configuration.
 * Determines behavior during Redis connection failures.
 */
public enum ConsistencyLevel {
    
    /**
     * High consistency: Requires strong data consistency.
     * 
     * Behavior on Redis disconnect:
     * - Immediately clear all L1 cache entries
     * - Prevents reading stale data during partition
     * - Favors CP in CAP theorem
     * 
     * Use cases: Inventory, pricing, sensitive state
     */
    HIGH,
    
    /**
     * Available consistency: Tolerates temporary inconsistency.
     * 
     * Behavior on Redis disconnect:
     * - Retain L1 cache entries
     * - Mark entries as STALE but continue serving
     * - Rely on natural TTL expiration
     * - Favors AP in CAP theorem
     * 
     * Use cases: Product details, static configs, eventually consistent data
     */
    AVAILABLE
}
