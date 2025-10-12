package com.smmpanel.entity;

import io.hypersistence.utils.hibernate.type.basic.PostgreSQLEnumType;
import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Type;
import org.hibernate.annotations.UpdateTimestamp;

@Data
@Entity
@Table(
        name = "youtube_accounts",
        indexes = {
            @Index(name = "idx_youtube_accounts_email", columnList = "email"),
            @Index(name = "idx_youtube_accounts_status", columnList = "status"),
            @Index(name = "idx_youtube_accounts_created_at", columnList = "created_at")
        })
@EqualsAndHashCode(callSuper = false)
@Slf4j
public class YouTubeAccount {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String email;

    @Column(nullable = false)
    private String password;

    @Enumerated(EnumType.STRING)
    @Type(value = PostgreSQLEnumType.class)
    @Column(name = "status", columnDefinition = "youtube_account_status")
    private YouTubeAccountStatus status = YouTubeAccountStatus.ACTIVE;

    @Column(name = "total_clips_created")
    private Integer totalClipsCreated = 0;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    @Column(name = "last_error_at")
    private LocalDateTime lastErrorAt;

    @Type(JsonBinaryType.class)
    @Column(name = "proxy_config", columnDefinition = "JSONB")
    private String proxyConfig;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
