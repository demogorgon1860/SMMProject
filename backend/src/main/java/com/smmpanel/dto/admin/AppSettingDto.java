package com.smmpanel.dto.admin;

import com.smmpanel.entity.AppSetting;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Wire shape for /admin/settings endpoints. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AppSettingDto {
    private String key;
    private String value;
    private String valueType;
    private String description;
    private LocalDateTime updatedAt;
    private String updatedBy;

    public static AppSettingDto from(AppSetting s) {
        return AppSettingDto.builder()
                .key(s.getKey())
                .value(s.getValue())
                .valueType(s.getValueType().name())
                .description(s.getDescription())
                .updatedAt(s.getUpdatedAt())
                .updatedBy(s.getUpdatedBy() != null ? s.getUpdatedBy().getUsername() : null)
                .build();
    }
}
