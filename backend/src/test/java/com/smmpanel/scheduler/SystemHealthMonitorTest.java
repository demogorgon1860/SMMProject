package com.smmpanel.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smmpanel.client.InstagramBotClient;
import com.smmpanel.client.InstagramBotClient.InstanceStatus;
import com.smmpanel.config.TelegramBotProperties;
import com.smmpanel.service.notification.TelegramBotService;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

/** Unit tests for the bot-down edge state machine in {@link SystemHealthMonitor}. */
class SystemHealthMonitorTest {

    private static final String URL = "http://45.142.211.90:8080";

    private TelegramBotProperties props;
    private TelegramBotService tg;
    private InstagramBotClient client;
    private StringRedisTemplate redis;
    private Map<Object, Object> hashStore; // in-memory stand-in for the per-instance Redis hash
    private SystemHealthMonitor monitor;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        props = new TelegramBotProperties();
        props.getHealth().setDownThreshold(2);

        tg = mock(TelegramBotService.class);
        when(tg.isHealthChannelOperational()).thenReturn(true);

        client = mock(InstagramBotClient.class);

        redis = mock(StringRedisTemplate.class);
        hashStore = new HashMap<>();
        HashOperations<String, Object, Object> hashOps = mock(HashOperations.class);
        when(redis.opsForHash()).thenReturn(hashOps);
        when(hashOps.get(anyString(), any())).thenAnswer(inv -> hashStore.get(inv.getArgument(1)));
        doAnswer(
                        inv -> {
                            hashStore.put(inv.getArgument(1), inv.getArgument(2));
                            return null;
                        })
                .when(hashOps)
                .put(anyString(), any(), any());
        when(hashOps.delete(anyString(), any()))
                .thenAnswer(
                        inv -> {
                            hashStore.remove(inv.getArgument(1));
                            return 1L;
                        });

        monitor = new SystemHealthMonitor(props, tg, client, redis);
    }

    private static InstanceStatus down() {
        return InstanceStatus.builder().baseUrl(URL).online(false).lastError("refused").build();
    }

    private static InstanceStatus up() {
        return InstanceStatus.builder().baseUrl(URL).online(true).build();
    }

    @Test
    void debouncesThenAlertsDownOnceAndRecovers() {
        when(client.getAllInstanceStatuses())
                .thenReturn(List.of(down())) // poll 1 — streak 1, below threshold, no alert
                .thenReturn(List.of(down())) // poll 2 — streak 2 → DOWN alert
                .thenReturn(List.of(down())) // poll 3 — already down, no repeat
                .thenReturn(List.of(up())); // poll 4 — recovery alert

        monitor.pollBotLiveness();
        monitor.pollBotLiveness();
        monitor.pollBotLiveness();
        monitor.pollBotLiveness();

        ArgumentCaptor<String> cap = ArgumentCaptor.forClass(String.class);
        verify(tg, times(2)).sendToHealthChat(cap.capture());
        assertThat(cap.getAllValues().get(0)).contains("НЕДОСТУПЕН").contains(URL);
        assertThat(cap.getAllValues().get(1)).contains("восстановлен");
    }

    @Test
    void singleTransientFailureDoesNotPage() {
        when(client.getAllInstanceStatuses())
                .thenReturn(List.of(down())) // one failed poll (e.g. deploy blip)
                .thenReturn(List.of(up())); // recovered before threshold

        monitor.pollBotLiveness();
        monitor.pollBotLiveness();

        verify(tg, never()).sendToHealthChat(anyString());
    }

    @Test
    void displayHostRewritesDockerAliasInAlertText() {
        props.getHealth().setDisplayHost("45.142.211.90");
        String dockerUrl = "http://host.docker.internal:8080";
        InstanceStatus dockerDown =
                InstanceStatus.builder().baseUrl(dockerUrl).online(false).lastError("x").build();

        when(client.getAllInstanceStatuses())
                .thenReturn(List.of(dockerDown))
                .thenReturn(List.of(dockerDown)); // 2nd poll → DOWN alert

        monitor.pollBotLiveness();
        monitor.pollBotLiveness();

        ArgumentCaptor<String> cap = ArgumentCaptor.forClass(String.class);
        verify(tg, times(1)).sendToHealthChat(cap.capture());
        assertThat(cap.getValue())
                .contains("http://45.142.211.90:8080")
                .doesNotContain("host.docker.internal");
    }

    @Test
    void doesNotReAlertWhenAlreadyDownAfterRestart() {
        // Simulate persisted Redis state from before a panel restart: already DOWN.
        hashStore.put("state", "DOWN");
        hashStore.put("fail_streak", "5");
        hashStore.put("down_since", Instant.now().toString());

        when(client.getAllInstanceStatuses()).thenReturn(List.of(down()));
        monitor.pollBotLiveness();

        verify(tg, never()).sendToHealthChat(anyString());
    }
}
