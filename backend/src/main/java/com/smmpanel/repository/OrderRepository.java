package com.smmpanel.repository;

import com.smmpanel.entity.Order;
import com.smmpanel.entity.OrderStatus;
import com.smmpanel.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface OrderRepository extends JpaRepository<Order, Long> {
    Page<Order> findByUser(User user, Pageable pageable);
    Page<Order> findByUserAndStatus(User user, OrderStatus status, Pageable pageable);
    Optional<Order> findByIdAndUser(Long id, User user);
    List<Order> findByStatus(OrderStatus status);
    
    @Query("SELECT o FROM Order o WHERE o.status IN :statuses")
    List<Order> findByStatusIn(@Param("statuses") List<OrderStatus> statuses);
    
    @Query("SELECT o FROM Order o WHERE o.createdAt >= :date")
    List<Order> findOrdersCreatedAfter(@Param("date") LocalDateTime date);
    
    @Query("SELECT COUNT(o) FROM Order o WHERE o.createdAt >= :date")
    Long countOrdersCreatedAfter(@Param("date") LocalDateTime date);
    
    @Query("SELECT SUM(o.charge) FROM Order o WHERE o.createdAt >= :date")
    Double sumRevenueAfter(@Param("date") LocalDateTime date);
}