package com.smmpanel.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PerfectPanelResponse<T> {
    private T data;
    private boolean success;
    private String message;
    private String error;
    private Integer errorCode;

    public static <T> PerfectPanelResponse<T> success(T data) {
        return PerfectPanelResponse.<T>builder()
                .data(data)
                .success(true)
                .build();
    }

    public static <T> PerfectPanelResponse<T> success(T data, String message) {
        return PerfectPanelResponse.<T>builder()
                .data(data)
                .success(true)
                .message(message)
                .build();
    }

    public static <T> PerfectPanelResponse<T> error(String message, Integer code) {
        return PerfectPanelResponse.<T>builder()
                .error(message)
                .errorCode(code)
                .success(false)
                .build();
    }
}