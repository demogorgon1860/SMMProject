package com.smmpanel.repository.jpa;

import com.smmpanel.entity.OrderEvent;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface OrderEventRepository extends JpaRepository<OrderEvent, Long> {

    List<OrderEvent> findByOrderIdOrderBySequenceNumber(Long orderId);

    List<OrderEvent> findByAggregateIdOrderBySequenceNumber(String aggregateId);

    Optional<OrderEvent> findByEventId(String eventId);

    boolean existsByEventId(String eventId);

    @Query(
            "SELECT e FROM OrderEvent e WHERE e.orderId = :orderId AND e.sequenceNumber >"
                    + " :afterSequence ORDER BY e.sequenceNumber")
    List<OrderEvent> findEventsAfterSequence(
            @Param("orderId") Long orderId, @Param("afterSequence") Long afterSequence);

    @Query(
            "SELECT e FROM OrderEvent e WHERE e.eventTimestamp BETWEEN :startTime AND :endTime"
                    + " ORDER BY e.eventTimestamp")
    List<OrderEvent> findEventsBetweenTimestamps(
            @Param("startTime") LocalDateTime startTime, @Param("endTime") LocalDateTime endTime);

    @Query(
            "SELECT e FROM OrderEvent e WHERE e.isProcessed = false AND e.retryCount < :maxRetries"
                    + " ORDER BY e.eventTimestamp")
    List<OrderEvent> findUnprocessedEvents(
            @Param("maxRetries") Integer maxRetries, Pageable pageable);

    @Query(
            "SELECT e FROM OrderEvent e WHERE e.eventType = :eventType ORDER BY e.eventTimestamp"
                    + " DESC")
    Page<OrderEvent> findByEventType(@Param("eventType") String eventType, Pageable pageable);

    @Query("SELECT MAX(e.sequenceNumber) FROM OrderEvent e WHERE e.orderId = :orderId")
    Optional<Long> findMaxSequenceNumberForOrder(@Param("orderId") Long orderId);

    @Query(
            "SELECT e FROM OrderEvent e WHERE e.correlationId = :correlationId ORDER BY"
                    + " e.eventTimestamp")
    List<OrderEvent> findByCorrelationId(@Param("correlationId") String correlationId);

    @Query("SELECT e FROM OrderEvent e WHERE e.userId = :userId ORDER BY e.eventTimestamp DESC")
    Page<OrderEvent> findByUserId(@Param("userId") Long userId, Pageable pageable);

    @Query(
            "SELECT e FROM OrderEvent e WHERE e.kafkaTopic = :topic AND e.kafkaPartition ="
                    + " :partition AND e.kafkaOffset >= :startOffset ORDER BY e.kafkaOffset")
    List<OrderEvent> findEventsForReplay(
            @Param("topic") String topic,
            @Param("partition") Integer partition,
            @Param("startOffset") Long startOffset);

    @Query("SELECT COUNT(e) FROM OrderEvent e WHERE e.orderId = :orderId")
    long countEventsByOrderId(@Param("orderId") Long orderId);

    @Query(
            "SELECT e FROM OrderEvent e WHERE e.isProcessed = false AND e.eventTimestamp <"
                    + " :cutoffTime")
    List<OrderEvent> findStaleUnprocessedEvents(@Param("cutoffTime") LocalDateTime cutoffTime);
}
