package com.smmpanel.repository;

import com.smmpanel.entity.YouTubeAccount;
import com.smmpanel.entity.YouTubeAccountStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface YouTubeAccountRepository extends JpaRepository<YouTubeAccount, Long> {
    List<YouTubeAccount> findByStatus(YouTubeAccountStatus status);
    List<YouTubeAccount> findByStatusOrderByDailyClipsCountAsc(YouTubeAccountStatus status);
}
