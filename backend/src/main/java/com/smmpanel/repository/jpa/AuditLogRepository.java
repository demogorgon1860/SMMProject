package com.smmpanel.repository.jpa;

import com.smmpanel.entity.AuditLog;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Repository for Audit Log operations */
@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    @Query(
            "SELECT a FROM AuditLog a WHERE "
                    + "(:entityType IS NULL OR a.entityType = :entityType) AND "
                    + "(:userId IS NULL OR a.userId = :userId) AND "
                    + "(:from IS NULL OR a.timestamp >= :from) AND "
                    + "(:to IS NULL OR a.timestamp <= :to) "
                    + "ORDER BY a.timestamp DESC")
    Page<AuditLog> findByFilters(
            @Param("entityType") String entityType,
            @Param("userId") Long userId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            Pageable pageable);

    @Query(
            "SELECT a FROM AuditLog a WHERE "
                    + "a.category = :category AND "
                    + "(:userId IS NULL OR a.userId = :userId) AND "
                    + "(:from IS NULL OR a.timestamp >= :from) AND "
                    + "(:to IS NULL OR a.timestamp <= :to) "
                    + "ORDER BY a.timestamp DESC")
    Page<AuditLog> findByCategory(
            @Param("category") AuditLog.AuditCategory category,
            @Param("userId") Long userId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            Pageable pageable);

    @Query(
            "SELECT a FROM AuditLog a WHERE "
                    + "a.isSuspicious = true AND "
                    + "(:from IS NULL OR a.timestamp >= :from) AND "
                    + "(:to IS NULL OR a.timestamp <= :to) "
                    + "ORDER BY a.timestamp DESC")
    Page<AuditLog> findSuspiciousActivities(
            @Param("from") LocalDateTime from, @Param("to") LocalDateTime to, Pageable pageable);

    List<AuditLog> findByEntityTypeAndEntityIdOrderByTimestampDesc(
            String entityType, Long entityId);

    List<AuditLog> findByUserIdOrderByTimestampDesc(Long userId, Pageable pageable);

    @Query(
            "SELECT a FROM AuditLog a WHERE "
                    + "a.severity = :severity AND "
                    + "a.timestamp >= :since "
                    + "ORDER BY a.timestamp DESC")
    List<AuditLog> findBySeveritySince(
            @Param("severity") AuditLog.AuditSeverity severity,
            @Param("since") LocalDateTime since);

    @Query(
            "SELECT COUNT(a) FROM AuditLog a WHERE "
                    + "a.userId = :userId AND "
                    + "a.action = :action AND "
                    + "a.timestamp >= :since")
    Long countUserActionsSince(
            @Param("userId") Long userId,
            @Param("action") String action,
            @Param("since") LocalDateTime since);
}
