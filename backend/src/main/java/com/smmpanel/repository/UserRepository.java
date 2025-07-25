package com.smmpanel.repository;

import com.smmpanel.entity.User;
import com.smmpanel.entity.UserRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.List;

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
}