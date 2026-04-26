package com.smmpanel.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

/**
 * Per-user notification toggles. Stored as JSONB so we can add new toggles (push, Telegram bot,
 * etc.) without further migrations.
 */
@Entity
@Table(name = "user_notification_prefs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserNotificationPrefs {

    @Id
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "prefs", nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private java.util.Map<String, Boolean> prefs = defaults();

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** Defaults — opt-in to transactional, opt-out of marketing. */
    public static java.util.Map<String, Boolean> defaults() {
        var m = new java.util.LinkedHashMap<String, Boolean>();
        m.put("orderCompleted", true);
        m.put("orderPartial", true);
        m.put("orderCancelled", true);
        m.put("deposit", true);
        m.put("weekly", false);
        m.put("promo", false);
        return m;
    }
}
