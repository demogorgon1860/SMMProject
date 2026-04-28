package com.smmpanel.repository.jpa;

import com.smmpanel.entity.SupportTicket;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface SupportTicketRepository extends JpaRepository<SupportTicket, Long> {

    List<SupportTicket> findByUserIdOrderByUpdatedAtDesc(Long userId);

    Optional<SupportTicket> findByIdAndUserId(Long id, Long userId);

    long countByUserId(Long userId);

    @Query(
            "SELECT t FROM SupportTicket t WHERE (:status IS NULL OR t.status = :status) ORDER BY"
                    + " t.updatedAt DESC")
    Page<SupportTicket> searchAll(@Param("status") SupportTicket.Status status, Pageable pageable);
}
