package com.smmpanel.repository.jpa;

import com.smmpanel.entity.OperatorLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

@Repository
public interface OperatorLogRepository
        extends JpaRepository<OperatorLog, Long>, JpaSpecificationExecutor<OperatorLog> {}
