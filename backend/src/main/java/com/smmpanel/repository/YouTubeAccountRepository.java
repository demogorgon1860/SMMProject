package com.smmpanel.repository;

import com.smmpanel.entity.YouTubeAccount;
import com.smmpanel.entity.YouTubeAccountStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface YouTubeAccountRepository extends JpaRepository<YouTubeAccount, Long> {
    @Query("SELECT ya FROM YouTubeAccount ya WHERE ya.status = 'ACTIVE' " +
           "AND (ya.lastClipDate != CURRENT_DATE OR ya.lastClipDate IS NULL OR ya.dailyClipsCount < ya.dailyLimit) " +
           "ORDER BY ya.dailyClipsCount ASC, ya.lastErrorAt ASC NULLS FIRST")
    List<YouTubeAccount> findAvailableAccounts();

    @Query("SELECT ya FROM YouTubeAccount ya WHERE ya.status = 'ACTIVE' " +
           "AND ya.dailyClipsCount < ya.dailyLimit " +
           "AND (ya.lastClipDate != CURRENT_DATE OR ya.lastClipDate IS NULL)")
    List<YouTubeAccount> findAccountsWithCapacity();

    List<YouTubeAccount> findByStatus(YouTubeAccountStatus status);

    @Query("SELECT ya FROM YouTubeAccount ya WHERE ya.lastClipDate = :date")
    List<YouTubeAccount> findByLastClipDate(LocalDate date);

    @Query("SELECT COUNT(ya) FROM YouTubeAccount ya WHERE ya.status = 'ACTIVE'")
    long countActiveAccounts();
}
