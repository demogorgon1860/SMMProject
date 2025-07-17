package com.smmpanel.repository;

import com.smmpanel.entity.ConversionCoefficient;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ConversionCoefficientRepository extends JpaRepository<ConversionCoefficient, Long> {
    Optional<ConversionCoefficient> findByServiceId(Long serviceId);
}
