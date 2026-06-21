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

    /**
     * Ids of the active services that form one quota group with the given base name. The base name
     * is a service name with its trailing {@code [geo]} bracket stripped (e.g. {@code "Instagram
     * Followers [Mix Gender]"}), so this matches every geo variant ({@code [USA/Europe]}, {@code
     * [Germany]}) AND both copies in the duplicated id-space (1-25 / 26-50). Used by {@code
     * OrderService.enforceUrlQuota} to aggregate the per-link cap across the services that share
     * the bot's account pool for the same action+gender. The {@code = :base} arm covers names that
     * have no geo bracket to strip (e.g. {@code "Instagram MIX GEO Followers"}), which then form a
     * group of their own.
     */
    @Query(
            "SELECT s.id FROM Service s WHERE s.active = true AND (s.name = :base OR s.name LIKE"
                    + " CONCAT(:base, ' [%'))")
    List<Long> findActiveQuotaGroupServiceIds(@Param("base") String base);

    boolean existsByName(String name);

    Optional<Service> findById(Long id);

    @Query(
            value = "SELECT COUNT(*) FROM user_service_access WHERE user_id = :userId",
            nativeQuery = true)
    long countAccessEntriesForUser(@Param("userId") Long userId);

    @Query(
            value =
                    "SELECT s.* FROM services s INNER JOIN user_service_access usa ON s.id ="
                            + " usa.service_id WHERE usa.user_id = :userId AND s.active = true"
                            + " ORDER BY s.id ASC",
            nativeQuery = true)
    List<Service> findActiveServicesForUser(@Param("userId") Long userId);

    @Query(
            value =
                    "SELECT CASE WHEN COUNT(*) > 0 THEN true ELSE false END FROM"
                            + " user_service_access WHERE user_id = :userId AND service_id ="
                            + " :serviceId",
            nativeQuery = true)
    boolean hasAccessToService(@Param("userId") Long userId, @Param("serviceId") Long serviceId);
}
