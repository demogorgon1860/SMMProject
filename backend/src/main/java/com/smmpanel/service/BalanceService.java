package com.smmpanel.service;

import com.smmpanel.entity.*;
import com.smmpanel.exception.InsufficientBalanceException;
import com.smmpanel.repository.BalanceTransactionRepository;
import com.smmpanel.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Slf4j
@Service
@RequiredArgsConstructor
public class BalanceService {

    private final UserRepository userRepository;
    private final BalanceTransactionRepository transactionRepository;

    @Transactional
    public void deductBalance(User user, BigDecimal amount, Order order) {
        BigDecimal currentBalance = user.getBalance();
        
        if (currentBalance.compareTo(amount) < 0) {
            throw new InsufficientBalanceException("Insufficient balance");
        }
        
        BigDecimal newBalance = currentBalance.subtract(amount);
        user.setBalance(newBalance);
        userRepository.save(user);
        
        // Create transaction record
        BalanceTransaction transaction = new BalanceTransaction();
        transaction.setUser(user);
        transaction.setOrder(order);
        transaction.setAmount(amount.negate());
        transaction.setBalanceBefore(currentBalance);
        transaction.setBalanceAfter(newBalance);
        transaction.setTransactionType(TransactionType.ORDER_PAYMENT);
        transaction.setDescription("Payment for order #" + order.getId());
        
        transactionRepository.save(transaction);
        
        log.info("Deducted {} from user {} for order {}", amount, user.getUsername(), order.getId());
    }

    @Transactional
    public void addBalance(User user, BigDecimal amount, BalanceDeposit deposit, String description) {
        BigDecimal currentBalance = user.getBalance();
        BigDecimal newBalance = currentBalance.add(amount);
        
        user.setBalance(newBalance);
        userRepository.save(user);
        
        // Create transaction record
        BalanceTransaction transaction = new BalanceTransaction();
        transaction.setUser(user);
        transaction.setDeposit(deposit);
        transaction.setAmount(amount);
        transaction.setBalanceBefore(currentBalance);
        transaction.setBalanceAfter(newBalance);
        transaction.setTransactionType(TransactionType.DEPOSIT);
        transaction.setDescription(description);
        
        transactionRepository.save(transaction);
        
        log.info("Added {} to user {} balance", amount, user.getUsername());
    }

    @Transactional
    public void refund(User user, BigDecimal amount, Order order, String reason) {
        BigDecimal currentBalance = user.getBalance();
        BigDecimal newBalance = currentBalance.add(amount);
        
        user.setBalance(newBalance);
        userRepository.save(user);
        
        // Create transaction record
        BalanceTransaction transaction = new BalanceTransaction();
        transaction.setUser(user);
        transaction.setOrder(order);
        transaction.setAmount(amount);
        transaction.setBalanceBefore(currentBalance);
        transaction.setBalanceAfter(newBalance);
        transaction.setTransactionType(TransactionType.REFUND);
        transaction.setDescription(reason);
        
        transactionRepository.save(transaction);
        
        log.info("Refunded {} to user {} for order {}", amount, user.getUsername(), order.getId());
    }

    public String getUserBalance(String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found"));
        return user.getBalance().toString();
    }
}