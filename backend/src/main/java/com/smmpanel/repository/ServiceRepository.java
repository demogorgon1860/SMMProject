package com.smmpanel.repository;

import com.smmpanel.entity.Service;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ServiceRepository extends JpaRepository<Service, Long> {
    List<Service> findByActiveOrderByIdAsc(Boolean active);
    List<Service> findByCategoryAndActiveOrderByIdAsc(String category, Boolean active);
    @Query("SELECT s FROM Service s WHERE s.active = true AND s.name LIKE %:searchTerm%")
    List<Service> searchActiveServices(@Param("searchTerm") String searchTerm);
    boolean existsByName(String name);
}