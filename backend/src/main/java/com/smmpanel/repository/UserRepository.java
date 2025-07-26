package com.smmpanel.repository;

import com.smmpanel.entity.User;
import com.smmpanel.entity.UserRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByUsername(String username);
    Optional<User> findByEmail(String email);
    @Query("SELECT u FROM User u WHERE u.apiKeyHash = :apiKeyHash")
    Optional<User> findByApiKeyHash(@Param("apiKeyHash") String apiKeyHash);
    boolean existsByUsername(String username);
    boolean existsByEmail(String email);
    List<User> findByRole(UserRole role);
    
    @Query("SELECT u FROM User u WHERE u.balance > 0 AND u.isActive = true")
    List<User> findActiveUsersWithBalance();

    @Query("SELECT u FROM User u WHERE u.apiKeyHash = :apiKeyHash AND u.isActive = true")
    Optional<User> findByApiKeyHashAndIsActiveTrue(@Param("apiKeyHash") String apiKeyHash);

    // ADDED: Missing custom query methods
    @Query("SELECT u FROM User u WHERE " +
           "(:username IS NULL OR u.username LIKE %:username%) AND " +
           "(:email IS NULL OR u.email LIKE %:email%) AND " +
           "(:role IS NULL OR u.role = :role) AND " +
           "(:isActive IS NULL OR u.isActive = :isActive)")
    Page<User> findUsersWithFilters(@Param("username") String username,
                                   @Param("email") String email,
                                   @Param("role") UserRole role,
                                   @Param("isActive") Boolean isActive,
                                   Pageable pageable);

    @Query("SELECT COUNT(u) FROM User u WHERE u.role = :role AND u.isActive = true")
    Long countByRoleAndIsActiveTrue(@Param("role") UserRole role);

    @Query("SELECT u FROM User u WHERE u.isActive = true AND " +
           "(u.username LIKE %:searchTerm% OR u.email LIKE %:searchTerm%)")
    Page<User> searchActiveUsers(@Param("searchTerm") String searchTerm, Pageable pageable);

    @Query("SELECT COUNT(u) > 0 FROM User u WHERE u.email = :email AND u.id != :id")
    boolean existsByEmailAndIdNot(@Param("email") String email, @Param("id") Long id);
}