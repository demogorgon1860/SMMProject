// Add these methods to existing OrderRepository.java
package com.smmpanel.repository;

import com.smmpanel.entity.Order;
import com.smmpanel.entity.OrderStatus;
import com.smmpanel.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.math.BigDecimal;

@Repository
public interface OrderRepository extends JpaRepository<Order, Long>, JpaSpecificationExecutor<Order> {
    @EntityGraph(attributePaths = {"user", "service"})
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
    
    @Query("SELECT COUNT(o) FROM Order o WHERE o.status = :status AND o.createdAt >= :date")
    Long countByStatusAndCreatedAtAfter(@Param("status") OrderStatus status, @Param("date") LocalDateTime date);
    
    @Query("SELECT DATE(o.createdAt) as date, SUM(o.charge) as revenue " +
           "FROM Order o WHERE o.createdAt >= :startDate " +
           "GROUP BY DATE(o.createdAt) ORDER BY DATE(o.createdAt)")
    List<Object[]> getDailyRevenue(@Param("startDate") LocalDateTime startDate);

    long countByStatusIn(List<OrderStatus> statuses);
    long countByStatus(OrderStatus status);
    
    @Query("SELECT AVG(o.charge) FROM Order o")
    Double calculateAverageOrderValue();

    @EntityGraph(attributePaths = {"user", "service"})
    @Query("SELECT o FROM Order o WHERE o.id = :id")
    Optional<Order> findByIdWithDetails(@Param("id") Long id);

    @Query("SELECT COUNT(o) FROM Order o WHERE o.user.id = :userId AND o.link = :link AND o.createdAt > :createdAt")
    long countByUserIdAndLinkAndCreatedAtAfter(@Param("userId") Long userId, @Param("link") String link, @Param("createdAt") LocalDateTime createdAt);

    @Query("SELECT o FROM Order o WHERE o.user.id = :userId AND o.createdAt > :createdAt")
    List<Order> findByUserIdAndCreatedAtAfter(@Param("userId") Long userId, @Param("createdAt") LocalDateTime createdAt);

    // User-specific queries
    Page<Order> findByUser_UsernameOrderByCreatedAtDesc(String username, Pageable pageable);
    Optional<Order> findByIdAndUser_Username(Long id, String username);
    Long countByUser_Username(String username);
    Long countByUser_UsernameAndStatus(String username, OrderStatus status);
    @Query("SELECT COALESCE(SUM(o.charge), 0) FROM Order o WHERE o.user.username = :username")
    BigDecimal sumChargeByUser_Username(@Param("username") String username);
    @Query("SELECT COALESCE(SUM(o.charge), 0) FROM Order o WHERE o.user.username = :username AND o.createdAt >= :date")
    BigDecimal sumChargeByUser_UsernameAndCreatedAtAfter(@Param("username") String username, @Param("date") LocalDateTime date);
    @Query("SELECT o FROM Order o WHERE o.status = :status AND o.createdAt < :date")
    List<Order> findByStatusAndCreatedAtBefore(@Param("status") OrderStatus status, @Param("date") LocalDateTime date);
    
    List<Order> findByStatusInAndCreatedAtBefore(
        List<OrderStatus> statuses, 
        LocalDateTime dateTime
    );
    
    long countByCreatedAtAfter(LocalDateTime dateTime);
    
    long countByUserIdAndCreatedAtAfter(Long userId, LocalDateTime dateTime);
}