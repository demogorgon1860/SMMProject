package com.smmpanel.service.refill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smmpanel.client.InstagramBotClient;
import com.smmpanel.dto.instagram.RefillCheckResult;
import com.smmpanel.dto.instagram.RefillJobDto;
import com.smmpanel.dto.instagram.RefillReportDto;
import com.smmpanel.dto.instagram.RefillStatusOutcome;
import com.smmpanel.dto.refill.RefillCheckResponse;
import com.smmpanel.entity.Order;
import com.smmpanel.entity.OrderStatus;
import com.smmpanel.entity.RefillCheck;
import com.smmpanel.entity.Service;
import com.smmpanel.entity.User;
import com.smmpanel.repository.jpa.OrderRepository;
import com.smmpanel.repository.jpa.RefillCheckRepository;
import com.smmpanel.repository.jpa.UserRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Coverage for the drop-rate formula, the {@link RefillCheckService#applyPollResult} scheduler
 * state machine (including the transient-vs-404 distinction), and the {@code startCheck} guards
 * (single-action only, idempotent reuse).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RefillCheckServiceTest {

    @Mock private OrderRepository orderRepository;
    @Mock private RefillCheckRepository refillCheckRepository;
    @Mock private UserRepository userRepository;
    @Mock private InstagramBotClient instagramBotClient;

    @InjectMocks private RefillCheckService service;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "reuseWindowMinutes", 3);
        ReflectionTestUtils.setField(service, "userRateLimit", 20);
        ReflectionTestUtils.setField(service, "userRateWindowMinutes", 60);
        ReflectionTestUtils.setField(service, "lostThresholdMinutes", 4);
        ReflectionTestUtils.setField(service, "maxAgeMinutes", 30);
        // Default: the order has no settled refill → the check targets the order itself.
        when(orderRepository.findFirstByRefillParentIdAndStatusInOrderByIdDesc(anyLong(), any()))
                .thenReturn(Optional.empty());
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // -----------------------------------------------------------------
    // drop-rate formula (static)
    // -----------------------------------------------------------------

    @Test
    @DisplayName("dropRate: matches the screenshot examples (21.5%, 40%)")
    void dropRate_examples() {
        assertThat(RefillCheckService.computeDropRate(215, 1000)).isEqualByComparingTo("21.50");
        assertThat(RefillCheckService.computeDropRate(40, 100)).isEqualByComparingTo("40.00");
    }

    @Test
    @DisplayName("dropRate: zero drop → 0%, full drop → 100%, zero ordered never divides by zero")
    void dropRate_edges() {
        assertThat(RefillCheckService.computeDropRate(0, 100)).isEqualByComparingTo("0.00");
        assertThat(RefillCheckService.computeDropRate(1000, 1000)).isEqualByComparingTo("100.00");
        assertThat(RefillCheckService.computeDropRate(50, 0)).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("dropRate: HALF_UP rounding to 2 dp")
    void dropRate_rounding() {
        assertThat(RefillCheckService.computeDropRate(1, 3)).isEqualByComparingTo("33.33");
        assertThat(RefillCheckService.computeDropRate(2, 3)).isEqualByComparingTo("66.67");
    }

    // -----------------------------------------------------------------
    // applyPollResult — scheduler state machine
    // -----------------------------------------------------------------

    private RefillCheck runningCheck(long id, int ageMinutes) {
        RefillCheck c =
                RefillCheck.builder()
                        .id(id)
                        .orderId(500L)
                        .userId(7L)
                        .status(RefillCheck.Status.RUNNING)
                        .orderedCount(1000)
                        .botJobId("rf_1")
                        .botInstanceUrl("http://bot")
                        .build();
        c.setRequestedAt(LocalDateTime.now().minusMinutes(ageMinutes));
        return c;
    }

    private RefillJobDto doneJob(RefillReportDto report) {
        return RefillJobDto.builder().status("done").reports(List.of(report)).build();
    }

    @Test
    @DisplayName("applyPollResult: done report → DONE with computed drop rate")
    void applyPollResult_done() {
        RefillCheck c = runningCheck(1L, 1);
        when(refillCheckRepository.findById(1L)).thenReturn(Optional.of(c));
        RefillReportDto rep =
                RefillReportDto.builder()
                        .status("done")
                        .count(1000)
                        .delivered(1000)
                        .matchable(1000)
                        .present(785)
                        .dropped(215)
                        .refillNeeded(215)
                        .earlyStopped(false)
                        .build();

        service.applyPollResult(1L, RefillStatusOutcome.found(doneJob(rep)));

        assertThat(c.getStatus()).isEqualTo(RefillCheck.Status.DONE);
        assertThat(c.getRefillNeeded()).isEqualTo(215);
        assertThat(c.getPresent()).isEqualTo(785);
        assertThat(c.getDropRate()).isEqualByComparingTo("21.50");
        assertThat(c.getCheckedAt()).isNotNull();
        verify(refillCheckRepository).save(c);
    }

    @Test
    @DisplayName("applyPollResult: running job stays RUNNING (no write)")
    void applyPollResult_running_staysRunning() {
        RefillCheck c = runningCheck(1L, 1);
        when(refillCheckRepository.findById(1L)).thenReturn(Optional.of(c));

        service.applyPollResult(
                1L, RefillStatusOutcome.found(RefillJobDto.builder().status("running").build()));

        assertThat(c.getStatus()).isEqualTo(RefillCheck.Status.RUNNING);
        verify(refillCheckRepository, never()).save(any());
    }

    @Test
    @DisplayName("applyPollResult: 404 (jobMissing) past lost-threshold → FAILED")
    void applyPollResult_missing_old_fails() {
        RefillCheck c = runningCheck(1L, 5); // older than lostThreshold=4
        when(refillCheckRepository.findById(1L)).thenReturn(Optional.of(c));

        service.applyPollResult(1L, RefillStatusOutcome.missing());

        assertThat(c.getStatus()).isEqualTo(RefillCheck.Status.FAILED);
        assertThat(c.getError()).contains("run it again");
        verify(refillCheckRepository).save(c);
    }

    @Test
    @DisplayName("applyPollResult: 404 while young stays RUNNING")
    void applyPollResult_missing_young_staysRunning() {
        RefillCheck c = runningCheck(1L, 2); // younger than lostThreshold=4
        when(refillCheckRepository.findById(1L)).thenReturn(Optional.of(c));

        service.applyPollResult(1L, RefillStatusOutcome.missing());

        assertThat(c.getStatus()).isEqualTo(RefillCheck.Status.RUNNING);
        verify(refillCheckRepository, never()).save(any());
    }

    @Test
    @DisplayName("applyPollResult: transient error under max-age does NOT fail a live job (fix #2)")
    void applyPollResult_transient_underMaxAge_staysRunning() {
        RefillCheck c = runningCheck(1L, 10); // > lostThreshold(4) but < maxAge(30)
        when(refillCheckRepository.findById(1L)).thenReturn(Optional.of(c));

        service.applyPollResult(1L, RefillStatusOutcome.transientError());

        assertThat(c.getStatus()).isEqualTo(RefillCheck.Status.RUNNING);
        verify(refillCheckRepository, never()).save(any());
    }

    @Test
    @DisplayName("applyPollResult: transient error past max-age ceiling → FAILED")
    void applyPollResult_transient_overMaxAge_fails() {
        RefillCheck c = runningCheck(1L, 31); // > maxAge=30
        when(refillCheckRepository.findById(1L)).thenReturn(Optional.of(c));

        service.applyPollResult(1L, RefillStatusOutcome.transientError());

        assertThat(c.getStatus()).isEqualTo(RefillCheck.Status.FAILED);
        verify(refillCheckRepository).save(c);
    }

    @Test
    @DisplayName("applyPollResult: per-report error (e.g. hidden counts) → FAILED, never drop=0")
    void applyPollResult_reportError_fails() {
        RefillCheck c = runningCheck(1L, 1);
        when(refillCheckRepository.findById(1L)).thenReturn(Optional.of(c));
        RefillReportDto rep =
                RefillReportDto.builder().status("error").error("like counts hidden").build();

        service.applyPollResult(1L, RefillStatusOutcome.found(doneJob(rep)));

        assertThat(c.getStatus()).isEqualTo(RefillCheck.Status.FAILED);
        assertThat(c.getError()).contains("hidden");
        assertThat(c.getRefillNeeded()).isNull(); // not coerced to 0
    }

    @Test
    @DisplayName("applyPollResult: already-finalized check is a no-op")
    void applyPollResult_finalized_noop() {
        RefillCheck c = runningCheck(1L, 1);
        c.setStatus(RefillCheck.Status.DONE);
        when(refillCheckRepository.findById(1L)).thenReturn(Optional.of(c));

        service.applyPollResult(1L, RefillStatusOutcome.missing());

        verify(refillCheckRepository, never()).save(any());
    }

    // -----------------------------------------------------------------
    // startCheck guards
    // -----------------------------------------------------------------

    private Order order(long id, String category) {
        Order o = new Order();
        o.setId(id);
        o.setStatus(OrderStatus.COMPLETED);
        o.setQuantity(1000);
        o.setIsRefill(false);
        o.setService(Service.builder().category(category).build());
        return o;
    }

    @Test
    @DisplayName("startCheck: combo service is rejected in v1 (single-action only)")
    void startCheck_combo_rejected() {
        User user = authAsUser();
        Order combo = order(99L, "INSTAGRAM_LIKE_COMMENT_FOLLOW");
        when(orderRepository.findByIdAndUser(99L, user)).thenReturn(Optional.of(combo));

        assertThatThrownBy(() -> service.startCheckForUser(99L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("single-action");
        verify(instagramBotClient, never()).refillCheck(anyString());
    }

    @Test
    @DisplayName("startCheck: reuses a recent in-flight RUNNING check instead of re-queueing")
    void startCheck_reuses_inflight() {
        User user = authAsUser();
        Order single = order(99L, "INSTAGRAM_LIKES");
        when(orderRepository.findByIdAndUser(99L, user)).thenReturn(Optional.of(single));
        RefillCheck inflight = runningCheck(7L, 1); // 1 min old, within reuse window (3)
        inflight.setOrderId(99L);
        when(refillCheckRepository.findFirstByOrderIdAndStatusOrderByRequestedAtDesc(
                        99L, RefillCheck.Status.RUNNING))
                .thenReturn(Optional.of(inflight));

        RefillCheckResponse resp = service.startCheckForUser(99L);

        assertThat(resp.getStatus()).isEqualTo("RUNNING");
        verify(instagramBotClient, never()).refillCheck(anyString());
        verify(refillCheckRepository, never()).save(any());
    }

    @Test
    @DisplayName("startCheck: happy path kicks off the bot and persists a RUNNING check")
    void startCheck_happy_persistsRunning() {
        User user = authAsUser();
        Order single = order(99L, "INSTAGRAM_FOLLOWERS");
        when(orderRepository.findByIdAndUser(99L, user)).thenReturn(Optional.of(single));
        when(refillCheckRepository.findFirstByOrderIdAndStatusOrderByRequestedAtDesc(
                        eq(99L), any()))
                .thenReturn(Optional.empty());
        when(instagramBotClient.refillCheck("99"))
                .thenReturn(
                        RefillCheckResult.builder()
                                .accepted(true)
                                .jobId("rf_42")
                                .instanceUrl("http://bot")
                                .build());
        when(refillCheckRepository.save(any(RefillCheck.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        RefillCheckResponse resp = service.startCheckForUser(99L);

        assertThat(resp.getStatus()).isEqualTo("RUNNING");
        verify(instagramBotClient).refillCheck("99");
        verify(refillCheckRepository).save(any(RefillCheck.class));
    }

    @Test
    @DisplayName("startCheck: targets the LATEST settled refill (not the original) when one exists")
    void startCheck_targets_latest_refill() {
        User user = authAsUser();
        Order original = order(99L, "INSTAGRAM_FOLLOWERS");
        when(orderRepository.findByIdAndUser(99L, user)).thenReturn(Optional.of(original));
        Order latestRefill = order(150L, "INSTAGRAM_FOLLOWERS");
        latestRefill.setQuantity(215);
        latestRefill.setIsRefill(true);
        when(orderRepository.findFirstByRefillParentIdAndStatusInOrderByIdDesc(eq(99L), any()))
                .thenReturn(Optional.of(latestRefill));
        when(refillCheckRepository.findFirstByOrderIdAndStatusOrderByRequestedAtDesc(eq(99L), any()))
                .thenReturn(Optional.empty());
        when(instagramBotClient.refillCheck("150"))
                .thenReturn(
                        RefillCheckResult.builder()
                                .accepted(true)
                                .jobId("rf_9")
                                .instanceUrl("http://bot")
                                .build());
        when(refillCheckRepository.save(any(RefillCheck.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        RefillCheckResponse resp = service.startCheckForUser(99L);

        assertThat(resp.getStatus()).isEqualTo("RUNNING");
        // The drop is measured against the latest refill (150), but the check is recorded under the
        // ORIGINAL order (99) and sized to that batch's quantity.
        assertThat(resp.getOrderId()).isEqualTo(99L);
        assertThat(resp.getOrderedCount()).isEqualTo(215);
        verify(instagramBotClient).refillCheck("150");
        verify(instagramBotClient, org.mockito.Mockito.never()).refillCheck("99");
    }

    private User authAsUser() {
        User user = User.builder().id(7L).username("u").build();
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(
                                "u", "n/a", List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        when(userRepository.findByUsername("u")).thenReturn(Optional.of(user));
        return user;
    }
}
