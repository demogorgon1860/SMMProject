package com.smmpanel.repository.jpa;

import com.smmpanel.entity.RefreshToken;
import com.smmpanel.entity.User;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByToken(String token);

    Optional<RefreshToken> findByTokenAndIsRevokedFalse(String token);

    List<RefreshToken> findByUserAndIsRevokedFalse(User user);

    List<RefreshToken> findByUser(User user);

    @Query(
            "SELECT rt FROM RefreshToken rt WHERE rt.user = :user AND rt.isRevoked = false AND"
                    + " rt.expiryDate > :now")
    List<RefreshToken> findActiveTokensByUser(
            @Param("user") User user, @Param("now") LocalDateTime now);

    @Modifying
    @Transactional
    @Query(
            "UPDATE RefreshToken rt SET rt.isRevoked = true, rt.revokedAt = :now"
                    + " WHERE rt.user = :user AND rt.isRevoked = false")
    int revokeAllUserTokens(@Param("user") User user, @Param("now") LocalDateTime now);

    @Modifying
    @Transactional
    @Query(
            "UPDATE RefreshToken rt SET rt.isRevoked = true, rt.revokedAt = :now"
                    + " WHERE rt.expiryDate < :now AND rt.isRevoked = false")
    int revokeExpiredTokens(@Param("now") LocalDateTime now);

    @Modifying
    @Transactional
    @Query("DELETE FROM RefreshToken rt WHERE rt.isRevoked = true AND rt.revokedAt < :cutoffDate")
    int deleteOldRevokedTokens(@Param("cutoffDate") LocalDateTime cutoffDate);

    @Query(
            "SELECT COUNT(rt) FROM RefreshToken rt WHERE rt.user = :user AND rt.isRevoked = false"
                    + " AND rt.expiryDate > :now")
    long countActiveTokensByUser(@Param("user") User user, @Param("now") LocalDateTime now);

    boolean existsByTokenAndIsRevokedFalse(String token);
}
