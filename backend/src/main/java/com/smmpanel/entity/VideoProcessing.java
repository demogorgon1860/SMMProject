package com.smmpanel.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "video_processing")
public class VideoProcessing {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumns({
        @JoinColumn(name = "order_id", referencedColumnName = "id"),
        @JoinColumn(name = "order_created_at", referencedColumnName = "created_at")
    })
    private Order order;

    @Column(name = "original_url", nullable = false, length = 500)
    private String originalUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "video_type")
    private VideoType videoType;

    @Column(name = "clip_created")
    private Boolean clipCreated = false;

    @Column(name = "clip_url", length = 500)
    private String clipUrl;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "youtube_account_id")
    private YouTubeAccount youtubeAccount;

    @Column(name = "processing_status", length = 50)
    private String processingStatus = "PENDING";

    @Column(name = "processing_attempts")
    private Integer processingAttempts = 0;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(columnDefinition = "JSONB")
    private String metadata;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}