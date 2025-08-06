package com.smmpanel.domain;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Entity
@Table(name = "users")
@Data
@NoArgsConstructor
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String username;

    @Column(nullable = false)
    private String password;

    @Column(nullable = false)
    private String email;

    @Column(name = "account_locked")
    private boolean accountLocked = false;

    @Column(name = "lock_time")
    private LocalDateTime lockTime;

    @Column(name = "failed_attempts")
    private int failedAttempts = 0;

    @Column(name = "last_login_attempt")
    private LocalDateTime lastLoginAttempt;

    @Column(name = "last_successful_login")
    private LocalDateTime lastSuccessfulLogin;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public void incrementFailedAttempts() {
        this.failedAttempts++;
        this.lastLoginAttempt = LocalDateTime.now();
    }

    public void resetFailedAttempts() {
        this.failedAttempts = 0;
        this.lastSuccessfulLogin = LocalDateTime.now();
        this.lastLoginAttempt = LocalDateTime.now();
    }

    public void lock() {
        this.accountLocked = true;
        this.lockTime = LocalDateTime.now();
    }

    public void unlock() {
        this.accountLocked = false;
        this.lockTime = null;
        this.failedAttempts = 0;
    }

    public boolean isAccountNonLocked() {
        return !accountLocked;
    }
}
