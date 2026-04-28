package com.smmpanel.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * Persisted application setting. Backs /admin/settings.
 *
 * <p>Values are stored as strings; {@link #valueType} drives parsing. Read access is cached (cache:
 * {@code app-settings}); writes evict the cache.
 */
@Entity
@Table(name = "app_settings")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = "key")
@ToString(exclude = "updatedBy")
public class AppSetting {

    public enum ValueType {
        STRING,
        NUMBER,
        BOOLEAN
    }

    @Id
    @Column(name = "key", length = 100, nullable = false)
    private String key;

    @Column(name = "value", nullable = false, columnDefinition = "TEXT")
    private String value;

    @Enumerated(EnumType.STRING)
    @Column(name = "value_type", length = 16, nullable = false)
    private ValueType valueType;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by_id")
    private User updatedBy;
}
