package com.smmpanel.aspect;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * Aspect to monitor query count for controller endpoints
 * Helps identify N+1 query problems in production
 */
@Aspect
@Component
@Slf4j
public class QueryCountMonitoringAspect {

    @PersistenceContext
    private EntityManager entityManager;
    
    @Value("${app.monitoring.query-count.enabled:false}")
    private boolean queryCountMonitoringEnabled;
    
    @Value("${app.monitoring.query-count.warn-threshold:5}")
    private int queryWarnThreshold;
    
    @Value("${app.monitoring.query-count.error-threshold:10}")
    private int queryErrorThreshold;

    /**
     * Monitor query count for all controller methods
     */
    @Around("execution(* com.smmpanel.controller.*.*(..))")
    public Object monitorControllerQueries(ProceedingJoinPoint joinPoint) throws Throwable {
        if (!queryCountMonitoringEnabled) {
            return joinPoint.proceed();
        }
        
        String methodName = joinPoint.getSignature().toShortString();
        
        // Get Hibernate statistics
        Session session = entityManager.unwrap(Session.class);
        SessionFactory sessionFactory = session.getSessionFactory();
        Statistics statistics = sessionFactory.getStatistics();
        
        long initialQueryCount = statistics.getQueryExecutionCount();
        long initialEntityLoadCount = statistics.getEntityLoadCount();
        long initialCollectionLoadCount = statistics.getCollectionLoadCount();
        
        long startTime = System.currentTimeMillis();
        
        try {
            Object result = joinPoint.proceed();
            
            long endTime = System.currentTimeMillis();
            long executionTime = endTime - startTime;
            
            long finalQueryCount = statistics.getQueryExecutionCount();
            long finalEntityLoadCount = statistics.getEntityLoadCount();
            long finalCollectionLoadCount = statistics.getCollectionLoadCount();
            
            long queryCount = finalQueryCount - initialQueryCount;
            long entityLoadCount = finalEntityLoadCount - initialEntityLoadCount;
            long collectionLoadCount = finalCollectionLoadCount - initialCollectionLoadCount;
            
            // Log based on query count thresholds
            if (queryCount >= queryErrorThreshold) {
                log.error("HIGH QUERY COUNT DETECTED - Method: {} | Queries: {} | Entities: {} | Collections: {} | Time: {}ms", 
                    methodName, queryCount, entityLoadCount, collectionLoadCount, executionTime);
            } else if (queryCount >= queryWarnThreshold) {
                log.warn("Elevated query count - Method: {} | Queries: {} | Entities: {} | Collections: {} | Time: {}ms", 
                    methodName, queryCount, entityLoadCount, collectionLoadCount, executionTime);
            } else if (log.isDebugEnabled()) {
                log.debug("Query stats - Method: {} | Queries: {} | Entities: {} | Collections: {} | Time: {}ms", 
                    methodName, queryCount, entityLoadCount, collectionLoadCount, executionTime);
            }
            
            return result;
            
        } catch (Throwable throwable) {
            long endTime = System.currentTimeMillis();
            long executionTime = endTime - startTime;
            
            long finalQueryCount = statistics.getQueryExecutionCount();
            long queryCount = finalQueryCount - initialQueryCount;
            
            log.error("Controller method failed - Method: {} | Queries before failure: {} | Time: {}ms | Error: {}", 
                methodName, queryCount, executionTime, throwable.getMessage());
            
            throw throwable;
        }
    }
    
    /**
     * Monitor specific service methods that are known to cause N+1 issues
     */
    @Around("execution(* com.smmpanel.service.OrderService.getUserOrders*(..))")
    public Object monitorOrderServiceQueries(ProceedingJoinPoint joinPoint) throws Throwable {
        return monitorServiceMethodQueries(joinPoint, "OrderService");
    }
    
    @Around("execution(* com.smmpanel.service.BalanceService.getTransactionHistory*(..))")
    public Object monitorBalanceServiceQueries(ProceedingJoinPoint joinPoint) throws Throwable {
        return monitorServiceMethodQueries(joinPoint, "BalanceService");
    }
    
    private Object monitorServiceMethodQueries(ProceedingJoinPoint joinPoint, String serviceName) throws Throwable {
        if (!queryCountMonitoringEnabled) {
            return joinPoint.proceed();
        }
        
        String methodName = serviceName + "." + joinPoint.getSignature().getName();
        
        Session session = entityManager.unwrap(Session.class);
        SessionFactory sessionFactory = session.getSessionFactory();
        Statistics statistics = sessionFactory.getStatistics();
        
        long initialQueryCount = statistics.getQueryExecutionCount();
        long startTime = System.currentTimeMillis();
        
        try {
            Object result = joinPoint.proceed();
            
            long endTime = System.currentTimeMillis();
            long executionTime = endTime - startTime;
            long queryCount = statistics.getQueryExecutionCount() - initialQueryCount;
            
            if (queryCount > 2) { // Service methods should typically use 1-2 queries max
                log.warn("Service method used {} queries - Method: {} | Time: {}ms", 
                    queryCount, methodName, executionTime);
            } else if (log.isDebugEnabled()) {
                log.debug("Service method stats - Method: {} | Queries: {} | Time: {}ms", 
                    methodName, queryCount, executionTime);
            }
            
            return result;
            
        } catch (Throwable throwable) {
            long endTime = System.currentTimeMillis();
            long executionTime = endTime - startTime;
            long queryCount = statistics.getQueryExecutionCount() - initialQueryCount;
            
            log.error("Service method failed - Method: {} | Queries: {} | Time: {}ms | Error: {}", 
                methodName, queryCount, executionTime, throwable.getMessage());
            
            throw throwable;
        }
    }
}