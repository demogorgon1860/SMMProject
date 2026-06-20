package com.smmpanel.scheduler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.smmpanel.config.OrderSerializationProperties;
import com.smmpanel.repository.jpa.OrderRepository;
import com.smmpanel.service.notification.TelegramBotService;
import com.smmpanel.service.order.OrderSerializationService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/** Unit tests for the authoritative backstop {@link OrderSerializationSweeper}. */
class OrderSerializationSweeperTest {

    private OrderRepository repo;
    private OrderSerializationService service;
    private OrderSerializationProperties props;
    private TelegramBotService telegram;
    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;
    private OrderSerializationSweeper sweeper;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        repo = mock(OrderRepository.class);
        service = mock(OrderSerializationService.class);
        props = new OrderSerializationProperties();
        telegram = mock(TelegramBotService.class);
        redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), anyString(), anyLong(), any())).thenReturn(true);
        sweeper = new OrderSerializationSweeper(repo, service, props, telegram, redis);
    }

    @Test
    void disabledIsANoOp() {
        props.setEnabled(false);
        sweeper.sweep();
        verifyNoInteractions(repo, service, telegram);
    }

    @Test
    void orphanLinksGetPumped() {
        when(repo.findLinksWithPendingAndNoActive(any(), any()))
                .thenReturn(List.of("urlA", "urlB"));
        when(repo.findStuckActiveLinks(any(), any(), any())).thenReturn(List.of());

        sweeper.sweep();

        verify(service).pumpUrlAsync("urlA");
        verify(service).pumpUrlAsync("urlB");
        verify(telegram, never()).sendToHealthChat(anyString());
    }

    @Test
    void stuckLinkAlertsOnce() {
        when(repo.findLinksWithPendingAndNoActive(any(), any())).thenReturn(List.of());
        when(repo.findStuckActiveLinks(any(), any(), any())).thenReturn(List.of("urlStuck"));

        sweeper.sweep();

        verify(telegram).sendToHealthChat(contains("urlStuck"));
        verify(service, never()).pumpUrlAsync(anyString()); // alert only, never auto-released
    }

    @Test
    void stuckAlertSuppressedByCooldown() {
        when(valueOps.setIfAbsent(anyString(), anyString(), anyLong(), any())).thenReturn(false);
        when(repo.findLinksWithPendingAndNoActive(any(), any())).thenReturn(List.of());
        when(repo.findStuckActiveLinks(any(), any(), any())).thenReturn(List.of("urlStuck"));

        sweeper.sweep();

        verify(telegram, never()).sendToHealthChat(anyString());
    }
}
