package com.smmpanel.repository.jpa;

import com.smmpanel.entity.OutboxEvent;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Repository for Transactional Outbox Events */
@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    /** Find unprocessed events ready for processing */
    @Query(
            """
        SELECT o FROM OutboxEvent o
        WHERE o.processed = false
        AND (o.nextRetryAt IS NULL OR o.nextRetryAt <= :now)
        AND o.retryCount < :maxRetries
        ORDER BY o.createdAt ASC
        """)
    List<OutboxEvent> findUnprocessedEvents(
            @Param("now") LocalDateTime now,
            @Param("maxRetries") int maxRetries,
            Pageable pageable);

    /** Find events by aggregate */
    List<OutboxEvent> findByAggregateTypeAndAggregateIdOrderByCreatedAtAsc(
            String aggregateType, String aggregateId);

    /** Find processed events older than specified time for cleanup */
    @Query(
            """
        SELECT o FROM OutboxEvent o
        WHERE o.processed = true
        AND o.processedAt < :cutoffTime
        """)
    List<OutboxEvent> findProcessedEventsOlderThan(@Param("cutoffTime") LocalDateTime cutoffTime);

    /** Delete processed events older than specified time */
    @Modifying
    @Query(
            """
        DELETE FROM OutboxEvent o
        WHERE o.processed = true
        AND o.processedAt < :cutoffTime
        """)
    int deleteProcessedEventsOlderThan(@Param("cutoffTime") LocalDateTime cutoffTime);

    /** Count unprocessed events */
    long countByProcessedFalse();

    /** Count failed events (max retries exceeded) */
    @Query(
            """
        SELECT COUNT(o) FROM OutboxEvent o
        WHERE o.processed = false
        AND o.retryCount >= :maxRetries
        """)
    long countFailedEvents(@Param("maxRetries") int maxRetries);
}
