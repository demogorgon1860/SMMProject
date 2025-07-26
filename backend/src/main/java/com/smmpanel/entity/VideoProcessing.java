package com.smmpanel.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "video_processing")
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
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
    @Column(name = "video_type")
    private VideoType videoType;

    @Column(name = "clip_created")
    @Builder.Default
    private Boolean clipCreated = false;

    @Column(name = "processing_status")
    @Enumerated(EnumType.STRING)
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