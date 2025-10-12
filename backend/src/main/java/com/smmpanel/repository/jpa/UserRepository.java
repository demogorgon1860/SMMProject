package com.smmpanel.repository.jpa;

import com.smmpanel.entity.User;
import com.smmpanel.entity.UserRole;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface UserRepository extends JpaRepository<User, Long>, JpaSpecificationExecutor<User> {
    Optional<User> findByUsername(String username);

    Optional<User> findByEmail(String email);

    // DEPRECATED: Use findByApiKeyHashAndIsActiveTrue instead for better performance
    @Deprecated
    @Query("SELECT u FROM User u WHERE u.apiKeyHash = :apiKeyHash")
    Optional<User> findByApiKeyHash(@Param("apiKeyHash") String apiKeyHash);

    boolean existsByUsername(String username);

    boolean existsByEmail(String email);

    List<User> findByRole(UserRole role);

    @Query("SELECT u FROM User u WHERE u.balance > 0 AND u.isActive = true")
    List<User> findActiveUsersWithBalance();

    // OPTIMIZED: Uses partial index idx_users_api_key_hash_active for better performance
    @Query("SELECT u FROM User u WHERE u.apiKeyHash = :hash AND u.isActive = true")
    Optional<User> findByApiKeyHashAndIsActiveTrue(@Param("hash") String hash);

    // Pessimistic locking for balance operations to prevent race conditions
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM User u WHERE u.id = :id")
    Optional<User> findByIdWithLock(@Param("id") Long id);

    // ADDED: Missing custom query methods
    @Query(
            "SELECT u FROM User u WHERE "
                    + "(:username IS NULL OR u.username LIKE %:username%) AND "
                    + "(:email IS NULL OR u.email LIKE %:email%) AND "
                    + "(:role IS NULL OR u.role = :role) AND "
                    + "(:isActive IS NULL OR u.isActive = :isActive)")
    Page<User> findUsersWithFilters(
            @Param("username") String username,
            @Param("email") String email,
            @Param("role") UserRole role,
            @Param("isActive") Boolean isActive,
            Pageable pageable);

    @Query("SELECT COUNT(u) FROM User u WHERE u.role = :role AND u.isActive = true")
    Long countByRoleAndIsActiveTrue(@Param("role") UserRole role);

    @Query(
            "SELECT u FROM User u WHERE u.isActive = true AND "
                    + "(u.username LIKE %:searchTerm% OR u.email LIKE %:searchTerm%)")
    Page<User> searchActiveUsers(@Param("searchTerm") String searchTerm, Pageable pageable);

    @Query("SELECT COUNT(u) > 0 FROM User u WHERE u.email = :email AND u.id != :id")
    boolean existsByEmailAndIdNot(@Param("email") String email, @Param("id") Long id);

    @Query(
            "SELECT u FROM User u WHERE "
                    + "(:role IS NULL OR u.role = :role) AND "
                    + "(:active IS NULL OR u.isActive = :active) AND "
                    + "(:minBalance IS NULL OR u.balance >= :minBalance)")
    Page<User> findUsersByRoleActiveBalance(
            @Param("role") UserRole role,
            @Param("active") Boolean active,
            @Param("minBalance") BigDecimal minBalance,
            Pageable pageable);

    // Find all active users with API keys for validation
    @Query("SELECT u FROM User u WHERE u.isActive = true AND u.apiKeyHash IS NOT NULL")
    List<User> findAllByIsActiveTrue();
}
