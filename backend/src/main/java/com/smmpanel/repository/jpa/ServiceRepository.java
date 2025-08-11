package com.smmpanel.repository.jpa;

import com.smmpanel.entity.Service;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ServiceRepository extends JpaRepository<Service, Long> {
    List<Service> findByActiveOrderByIdAsc(Boolean active);

    List<Service> findByActiveTrue();

    List<Service> findByCategoryAndActiveOrderByIdAsc(String category, Boolean active);

    @Query(
            "SELECT s FROM Service s WHERE s.active = true AND s.name LIKE CONCAT('%', :searchTerm,"
                    + " '%')")
    Page<Service> searchActiveServices(@Param("searchTerm") String searchTerm, Pageable pageable);

    boolean existsByName(String name);

    Optional<Service> findById(Long id);
}
