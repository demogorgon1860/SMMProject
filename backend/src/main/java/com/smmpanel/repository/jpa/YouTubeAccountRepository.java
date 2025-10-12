package com.smmpanel.repository.jpa;

import com.smmpanel.entity.YouTubeAccount;
import com.smmpanel.entity.YouTubeAccountStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
public interface YouTubeAccountRepository extends JpaRepository<YouTubeAccount, Long> {
    @Query(
            "SELECT ya FROM YouTubeAccount ya WHERE ya.status = 'ACTIVE' "
                    + "ORDER BY ya.lastErrorAt ASC NULLS FIRST")
    List<YouTubeAccount> findAvailableAccounts();

    @Query("SELECT ya FROM YouTubeAccount ya WHERE ya.status = 'ACTIVE'")
    List<YouTubeAccount> findAccountsWithCapacity();

    List<YouTubeAccount> findByStatus(YouTubeAccountStatus status);

    @Query("SELECT COUNT(ya) FROM YouTubeAccount ya WHERE ya.status = 'ACTIVE'")
    long countActiveAccounts();

    Optional<YouTubeAccount> findFirstByStatus(YouTubeAccountStatus status);
}
