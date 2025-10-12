package com.smmpanel.repository.redis;

import org.springframework.stereotype.Repository;

/**
 * Marker interface for Redis repository package. This ensures Spring Data Redis properly scans this
 * package even when no actual Redis repositories are defined yet.
 */
@Repository
public interface RedisRepositoryMarker {
    // Marker interface - no methods needed
}
