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
public class PerfectPanelResponse {
    private Object order;
    private Object orders;
    private Object services;
    private String balance;
    private String error;
    private Integer errorCode;

    public static PerfectPanelResponse success(Object data) {
        if (data instanceof OrderResponse) {
            return PerfectPanelResponse.builder().order(data).build();
        }
        return PerfectPanelResponse.builder().orders(data).build();
    }

    public static PerfectPanelResponse error(String message, Integer code) {
        return PerfectPanelResponse.builder()
                .error(message)
                .errorCode(code)
                .build();
    }
}