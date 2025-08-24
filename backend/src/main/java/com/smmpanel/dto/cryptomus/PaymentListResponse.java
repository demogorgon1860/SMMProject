package com.smmpanel.dto.cryptomus;

import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentListResponse {
    private List<Map<String, Object>> items;
    private Integer currentPage;
    private Integer perPage;
    private Integer total;
}
