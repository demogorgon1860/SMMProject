package com.smmpanel.repository.jpa;

import com.smmpanel.entity.User;
import com.smmpanel.entity.UserRole;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
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

    /**
     * Fast-path API-key resolution. The lookup hash is a deterministic SHA-512 keyed by the
     * application-wide global salt, so the same plaintext key always produces the same value here
     * regardless of which user owns it. Backed by partial unique index {@code
     * idx_users_api_key_lookup_hash} (added in migration v2026.05) — at most one row per non-null
     * lookup hash, so this resolves in O(1). Caller must still re-verify against the
     * per-user-salted {@code apiKeyHash} as defence in depth.
     */
    @Query("SELECT u FROM User u WHERE u.apiKeyLookupHash = :lookupHash AND u.isActive = true")
    Optional<User> findByApiKeyLookupHashAndIsActiveTrue(@Param("lookupHash") String lookupHash);

    // NOTE: a JPQL @Lock(PESSIMISTIC_WRITE) "findByIdWithLock" used to live here. Do NOT re-add ad-hoc
    // User locking — lock via BalanceService.lockAndRefreshUser / lockUserForUpdate ONLY. That helper
    // encodes BOTH hard-won User#856 incident lessons: (1) a JPQL/native query auto-flushes the
    // persistence context first, so it must never run while a dirty, stale User sits in the context
    // (guarded by locking at the top of the transaction); (2) em.lock/em.find/em.refresh with a
    // LockModeType on an already-managed versioned entity VERSION-CHECKS the cached @Version and
    // throws StaleObjectStateException on any concurrent commit — the lock must be an id-only query.

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

    /**
     * Atomic compare-and-set: flip {@code welcome_credit_granted_at} from NULL to {@code :now} for
     * exactly one verify request. Returns 1 if this caller wins (and may now safely credit the
     * wallet), 0 if another concurrent verify already set it (must NOT credit again).
     *
     * <p>This is the only mutation point for this column in the codebase, so the row-count return
     * value is a reliable idempotency signal.
     */
    @Modifying
    @Query(
            "UPDATE User u SET u.welcomeCreditGrantedAt = :now"
                    + " WHERE u.id = :userId AND u.welcomeCreditGrantedAt IS NULL")
    int markWelcomeCreditGranted(@Param("userId") Long userId, @Param("now") LocalDateTime now);

    /**
     * Soft-deleted users whose grace window has elapsed — fed to the daily hard-delete cron in
     * {@code AccountDeletionScheduler}. Returns at most {@code limit} rows so a long-running purge
     * can't lock a giant batch in one transaction.
     */
    @Query(
            value =
                    "SELECT * FROM users WHERE deleted_at IS NOT NULL AND deleted_at < :cutoff "
                            + "ORDER BY deleted_at ASC LIMIT :limit",
            nativeQuery = true)
    List<User> findSoftDeletedBefore(
            @Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);
}
