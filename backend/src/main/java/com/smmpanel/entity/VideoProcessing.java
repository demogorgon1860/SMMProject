package com.smmpanel.entity;

import jakarta.persistence.*;
import java.sql.Types;
import java.time.LocalDateTime;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;

@Data
@Entity
@Table(
        name = "video_processing",
        indexes = {
            @Index(name = "idx_video_processing_order_id", columnList = "order_id"),
            @Index(name = "idx_video_processing_status", columnList = "status"),
            @Index(name = "idx_video_processing_video_id", columnList = "video_id"),
            @Index(name = "idx_video_processing_created_at", columnList = "created_at")
        })
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
@Slf4j
public class VideoProcessing {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @Column(name = "original_url", nullable = false, length = 500)
    private String originalUrl;

    @Column(name = "clip_url", length = 500)
    private String clipUrl;

    @Column(name = "video_id", length = 100)
    private String videoId;

    @Enumerated(EnumType.STRING)
    @Column(name = "video_type", columnDefinition = "video_type")
    @JdbcTypeCode(Types.OTHER)
    private VideoType videoType;

    @Column(name = "clip_created")
    @Builder.Default
    private Boolean clipCreated = false;

    @Column(name = "status", nullable = false, length = 32)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private VideoProcessingStatus status = VideoProcessingStatus.PENDING;

    @Column(name = "processing_attempts")
    @Builder.Default
    private Integer processingAttempts = 0;

    @Column(name = "last_processed_at")
    private LocalDateTime lastProcessedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "last_error_at")
    private LocalDateTime lastErrorAt;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "youtube_account_id")
    private Long youtubeAccountId;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    // Helper methods for backward compatibility
    public String getProcessingStatus() {
        return status != null ? status.name() : "PENDING";
    }

    public void setProcessingStatus(String status) {
        this.status = VideoProcessingStatus.valueOf(status);
    }

    public boolean isClipCreated() {
        return Boolean.TRUE.equals(this.clipCreated);
    }
}
