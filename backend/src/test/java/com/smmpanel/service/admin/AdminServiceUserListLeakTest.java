package com.smmpanel.service.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smmpanel.client.InstagramBotClient;
import com.smmpanel.dto.admin.UserAdminDto;
import com.smmpanel.entity.User;
import com.smmpanel.entity.UserRole;
import com.smmpanel.repository.jpa.BalanceDepositRepository;
import com.smmpanel.repository.jpa.ConversionCoefficientRepository;
import com.smmpanel.repository.jpa.OperatorLogRepository;
import com.smmpanel.repository.jpa.OrderRepository;
import com.smmpanel.repository.jpa.ServiceRepository;
import com.smmpanel.repository.jpa.UserRepository;
import com.smmpanel.service.balance.BalanceService;
import com.smmpanel.service.core.AuditService;
import com.smmpanel.service.notification.DailyProfitService;
import com.smmpanel.service.notification.TelegramNotificationService;
import com.smmpanel.service.order.state.OrderStateManager;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.redis.core.RedisTemplate;

/**
 * Critical regression guard: {@code GET /api/v2/admin/users} must NEVER leak {@code passwordHash},
 * {@code apiKeyHash}, {@code apiKeySalt}, or any password column. The fix is the {@link
 * UserAdminDto} projection — this test ensures that:
 *
 * <ol>
 *   <li>The service returns {@code UserAdminDto} instances (not the {@code User} entity itself).
 *   <li>JSON serialization of {@code UserAdminDto} does not contain any password / api-key fields.
 *   <li>{@code apiKeyConfigured} is a sanitized boolean derived from {@code apiKeyHash != null} —
 *       i.e. the boolean is exposed but the hash itself is not.
 * </ol>
 *
 * Delete the {@code UserAdminDto} mapping in {@code AdminService.getUsers} and this test fails
 * loudly — that's exactly what we want for a security-relevant regression.
 */
@ExtendWith(MockitoExtension.class)
class AdminServiceUserListLeakTest {

    @Mock private OrderRepository orderRepository;
    @Mock private UserRepository userRepository;
    @Mock private ServiceRepository serviceRepository;
    @Mock private ConversionCoefficientRepository coefficientRepository;
    @Mock private OperatorLogRepository operatorLogRepository;
    @Mock private BalanceService balanceService;
    @Mock private OrderStateManager orderStateManager;
    @Mock private AuditService auditService;
    @Mock private BalanceDepositRepository balanceDepositRepository;
    @Mock private RedisTemplate<String, Object> redisTemplate;
    @Mock private InstagramBotClient instagramBotClient;
    @Mock private DailyProfitService dailyProfitService;
    @Mock private TelegramNotificationService telegramNotificationService;

    @InjectMocks private AdminService adminService;

    private static User userWithSecrets() {
        return User.builder()
                .id(1L)
                .username("admin_subject")
                .email("a@b.com")
                .passwordHash("$2a$10$EX_PRESERVED_BCRYPT_HASH_DO_NOT_LEAK")
                .apiKeyHash("dead_beef_api_key_hash_must_not_leak")
                .apiKeySalt("salt_must_not_leak")
                .role(UserRole.USER)
                .balance(new BigDecimal("100.00"))
                .totalSpent(new BigDecimal("50.00"))
                .isActive(true)
                .emailVerified(true)
                .twoFactorEnabled(false)
                .build();
    }

    @Test
    @DisplayName("getUsers returns UserAdminDto (not User entity) — type guard")
    void getUsers_returns_dto_type() {
        User u = userWithSecrets();
        Page<User> page = new PageImpl<>(List.of(u));
        when(userRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(page);
        when(orderRepository.countOrdersByUserIds(any())).thenReturn(List.of());

        Map<String, Object> result = adminService.getUsers(null, null, Pageable.unpaged());

        Object users = result.get("users");
        assertThat(users).isInstanceOf(List.class);
        List<?> list = (List<?>) users;
        assertThat(list).hasSize(1);
        assertThat(list.get(0))
                .as("Listing must use UserAdminDto, never the User entity")
                .isInstanceOf(UserAdminDto.class);
    }

    @Test
    @DisplayName("UserAdminDto JSON: no passwordHash, apiKeyHash, apiKeySalt, password fields")
    @SuppressWarnings("unchecked")
    void dto_json_has_no_secret_fields() throws Exception {
        User u = userWithSecrets();
        Page<User> page = new PageImpl<>(List.of(u));
        when(userRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(page);
        when(orderRepository.countOrdersByUserIds(any())).thenReturn(List.of());

        Map<String, Object> result = adminService.getUsers(null, null, Pageable.unpaged());

        ObjectMapper om = new ObjectMapper();
        String json = om.writeValueAsString(result);

        // Surface-level forbid list — these strings should NEVER appear in the response payload.
        assertThat(json)
                .as("admin users response must not contain passwordHash field")
                .doesNotContain("passwordHash");
        assertThat(json)
                .as("admin users response must not contain apiKeyHash field")
                .doesNotContain("apiKeyHash");
        assertThat(json)
                .as("admin users response must not contain apiKeySalt field")
                .doesNotContain("apiKeySalt");
        assertThat(json)
                .as("admin users response must not contain a password field")
                .doesNotContain("\"password\"");

        // Belt and braces — confirm the actual hash payload doesn't appear in serialized output.
        assertThat(json).doesNotContain("EX_PRESERVED_BCRYPT_HASH");
        assertThat(json).doesNotContain("dead_beef_api_key_hash");
        assertThat(json).doesNotContain("salt_must_not_leak");
    }

    @Test
    @DisplayName("UserAdminDto: apiKeyConfigured=true if hash present, false if absent")
    void apiKey_configured_boolean_from_hash() {
        User withKey = userWithSecrets();
        User withoutKey = userWithSecrets();
        withoutKey.setApiKeyHash(null);
        Page<User> page = new PageImpl<>(List.of(withKey, withoutKey));
        when(userRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(page);
        when(orderRepository.countOrdersByUserIds(any())).thenReturn(List.of());

        Map<String, Object> result = adminService.getUsers(null, null, Pageable.unpaged());
        @SuppressWarnings("unchecked")
        List<UserAdminDto> dtos = (List<UserAdminDto>) result.get("users");

        assertThat(dtos.get(0).isApiKeyConfigured()).isTrue();
        assertThat(dtos.get(1).isApiKeyConfigured()).isFalse();
    }

    @Test
    @DisplayName("UserAdminDto: status string derived from isActive (active|suspended)")
    void status_derives_from_isActive() {
        User active = userWithSecrets();
        User suspended = userWithSecrets();
        suspended.setActive(false);
        Page<User> page = new PageImpl<>(List.of(active, suspended));
        when(userRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(page);
        when(orderRepository.countOrdersByUserIds(any())).thenReturn(List.of());

        Map<String, Object> result = adminService.getUsers(null, null, Pageable.unpaged());
        @SuppressWarnings("unchecked")
        List<UserAdminDto> dtos = (List<UserAdminDto>) result.get("users");

        assertThat(dtos.get(0).getStatus()).isEqualTo("active");
        assertThat(dtos.get(1).getStatus()).isEqualTo("suspended");
    }

    @Test
    @DisplayName("UserAdminDto: pagination metadata is exposed at the response root")
    void response_carries_pagination_metadata() {
        Page<User> page = new PageImpl<>(List.of(userWithSecrets()));
        when(userRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(page);
        when(orderRepository.countOrdersByUserIds(any())).thenReturn(List.of());

        Map<String, Object> result = adminService.getUsers(null, null, Pageable.unpaged());

        assertThat(result)
                .containsKeys("users", "totalPages", "totalElements", "currentPage", "pageSize");
    }
}
