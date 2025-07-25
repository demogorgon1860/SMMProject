package com.smmpanel.repository;

import com.smmpanel.entity.ConversionCoefficient;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Repository for managing conversion coefficients
 */
@Repository
public interface ConversionCoefficientRepository extends JpaRepository<ConversionCoefficient, Long> {

    @Query("SELECT cc FROM ConversionCoefficient cc WHERE cc.serviceId = :serviceId AND cc.withoutClip = :withoutClip")
    Optional<ConversionCoefficient> findByServiceIdAndWithoutClip(@Param("serviceId") Long serviceId, 
                                                               @Param("withoutClip") Boolean withoutClip);

    @Query("SELECT cc FROM ConversionCoefficient cc WHERE cc.serviceId = :serviceId ORDER BY cc.updatedAt DESC")
    java.util.List<ConversionCoefficient> findByServiceIdOrderByUpdatedAtDesc(@Param("serviceId") Long serviceId);

    @Query("SELECT cc FROM ConversionCoefficient cc WHERE cc.withoutClip = :withoutClip")
    java.util.List<ConversionCoefficient> findByWithoutClip(@Param("withoutClip") Boolean withoutClip);
    
    // Legacy method for backward compatibility
    Optional<ConversionCoefficient> findByServiceId(Long serviceId);
}
