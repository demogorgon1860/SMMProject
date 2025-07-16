package com.smmpanel.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "youtube_accounts")
@EqualsAndHashCode(callSuper = false)
public class YouTubeAccount {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String username;

    @Column(nullable = false)
    private String email;

    @Enumerated(EnumType.STRING)
    private YouTubeAccountStatus status = YouTubeAccountStatus.ACTIVE;

    @Column(name = "daily_clips_count")
    private Integer dailyClipsCount = 0;

    @Column(name = "last_clip_date")
    private LocalDate lastClipDate;

    @Column(name = "daily_limit")
    private Integer dailyLimit = 50;

    @Column(name = "total_clips_created")
    private Integer totalClipsCreated = 0;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    @Column(name = "last_error_at")
    private LocalDateTime lastErrorAt;

    @Column(name = "proxy_config", columnDefinition = "JSONB")
    private String proxyConfig;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}