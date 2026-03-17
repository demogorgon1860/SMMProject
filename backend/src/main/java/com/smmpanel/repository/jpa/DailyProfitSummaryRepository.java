package com.smmpanel.repository.jpa;

import com.smmpanel.entity.DailyProfitSummary;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface DailyProfitSummaryRepository extends JpaRepository<DailyProfitSummary, Long> {

    Optional<DailyProfitSummary> findByReportDate(LocalDate reportDate);
}
