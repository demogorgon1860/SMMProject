package com.smmpanel.dto.profile;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Roll-up of an account's lifetime activity for the Profile → Account "Lifetime stats" tile.
 * Eventually-consistent (60s cache); we don't bother evicting on every order. Field semantics:
 *
 * <ul>
 *   <li>{@code totalSpent} — sum of {@code charge} on COMPLETED + PARTIAL orders, matching the same
 *       realized-revenue rule {@code DailyProfitService} uses
 *   <li>{@code memberSince} — {@code users.created_at}, never null
 *   <li>{@code firstOrderAt}/{@code lastOrderAt} — null while the user has placed zero orders
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LifetimeStatsDto {
    private long ordersTotal;
    private long ordersCompleted;
    private long ordersPartial;
    private long ordersCancelled;
    private BigDecimal totalSpent;
    private long ticketsTotal;
    private long refillsRequested;
    private LocalDateTime memberSince;
    private LocalDateTime firstOrderAt;
    private LocalDateTime lastOrderAt;
}
