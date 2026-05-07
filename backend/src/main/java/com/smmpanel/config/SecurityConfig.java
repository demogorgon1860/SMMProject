package com.smmpanel.config;

import com.smmpanel.repository.jpa.UserRepository;
import com.smmpanel.security.*;
import java.util.Arrays;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthFilter;
    private final ApiKeyAuthenticationFilter apiKeyAuthFilter;
    private final UserRepository userRepository;
    private final CustomUserDetailsService customUserDetailsService;
    private final AppProperties appProperties;

    public SecurityConfig(
            JwtAuthenticationFilter jwtAuthFilter,
            @Lazy ApiKeyAuthenticationFilter apiKeyAuthFilter,
            UserRepository userRepository,
            CustomUserDetailsService customUserDetailsService,
            AppProperties appProperties) {
        this.jwtAuthFilter = jwtAuthFilter;
        this.apiKeyAuthFilter = apiKeyAuthFilter;
        this.userRepository = userRepository;
        this.customUserDetailsService = customUserDetailsService;
        this.appProperties = appProperties;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http, RateLimitFilter rateLimitFilter, MaintenanceFilter maintenanceFilter)
            throws Exception {
        http.cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(
                        csrf ->
                                csrf.ignoringRequestMatchers(
                                                "/api/**",
                                                "/actuator/**",
                                                "/v3/api-docs/**",
                                                "/swagger-ui/**")
                                        .csrfTokenRepository(
                                                CookieCsrfTokenRepository
                                                        .withHttpOnlyFalse())) // Enable CSRF for
                // web endpoints,
                // disable for API
                .sessionManagement(
                        session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(
                        auth ->
                                auth
                                        // CRITICAL: Allow CORS preflight OPTIONS requests FIRST
                                        .requestMatchers(HttpMethod.OPTIONS, "/**")
                                        .permitAll()
                                        .requestMatchers(
                                                // Authentication endpoints — explicitly listed
                                                // instead of /auth/** so {@code /me}, /profile,
                                                // /logout, /logout-all are NOT publicly
                                                // reachable. The previous wildcard let anonymous
                                                // requests reach AuthService.getCurrentUser(),
                                                // which then surfaced as a generic 500 (no auth
                                                // context to read a username from). Anything
                                                // identity-bearing must require a JWT, anything
                                                // that BOOTSTRAPS or RESETS a JWT can be public.
                                                "/api/v*/auth/login",
                                                "/api/v*/auth/register",
                                                "/api/v*/auth/refresh",
                                                "/api/v*/auth/forgot-password",
                                                "/api/v*/auth/reset-password",
                                                "/api/v*/auth/verify-email",
                                                "/api/v*/auth/resend-verification",
                                                "/api/auth/login",
                                                "/api/auth/register",
                                                "/api/auth/refresh",
                                                "/api/auth/forgot-password",
                                                "/api/auth/reset-password",
                                                "/api/auth/verify-email",
                                                "/api/auth/resend-verification",

                                                // Service endpoints (both singular and plural
                                                // patterns)
                                                "/api/v*/services",
                                                "/api/v*/services/**",
                                                "/api/v*/service/**",

                                                // Health checks (singular - singleton)
                                                "/api/v*/health",
                                                "/api/v*/health/**",
                                                "/actuator/health",
                                                "/actuator/health/**",
                                                "/actuator/**",

                                                // Webhooks (plural - collections)
                                                "/api/v*/webhooks/**",
                                                "/api/webhook/**",
                                                "/api/v*/payments/cryptomus/callback",

                                                // Public landing pages (no auth)
                                                "/api/v*/stats/public",
                                                "/api/v*/stats/recent-orders",
                                                "/api/v*/faq",
                                                "/api/v*/faq/**",

                                                // Telegram Bot webhook (no JWT — Telegram sends its
                                                // own secret)
                                                "/api/telegram/webhook",

                                                // API Documentation
                                                "/v3/api-docs/**",
                                                "/swagger-ui/**",
                                                "/swagger-ui.html",
                                                "/swagger-resources/**",
                                                "/webjars/**",

                                                // Error and debug
                                                "/api/debug/**",
                                                "/error")
                                        .permitAll()

                                        // Admin endpoints (following REST pattern)
                                        .requestMatchers("/api/v*/admin/**", "/api/admin/**")
                                        .hasRole("ADMIN")

                                        // Operator endpoints
                                        .requestMatchers("/api/v*/operator/**")
                                        .hasAnyRole("OPERATOR", "ADMIN")

                                        // User-specific endpoints (require authentication)
                                        .requestMatchers(
                                                "/api/v*/orders/**",
                                                "/api/v*/users/**",
                                                "/api/v*/payments/**",
                                                "/api/v*/transactions/**",
                                                "/api/v*/profile/**")
                                        .authenticated()
                                        .anyRequest()
                                        .authenticated())
                .exceptionHandling(
                        exception ->
                                exception
                                        .authenticationEntryPoint(
                                                (request, response, authException) -> {
                                                    response.setStatus(401);
                                                    response.setContentType("application/json");
                                                    response.getWriter()
                                                            .write("{\"error\":\"Unauthorized\"}");
                                                })
                                        .accessDeniedHandler(
                                                (request, response, accessDeniedException) -> {
                                                    response.setStatus(403);
                                                    response.setContentType("application/json");
                                                    response.getWriter()
                                                            .write("{\"error\":\"Access Denied\"}");
                                                }))
                .headers(
                        headers ->
                                headers.frameOptions(frameOptions -> frameOptions.sameOrigin())
                                        .contentTypeOptions(
                                                contentTypeOptions -> contentTypeOptions.disable())
                                        .httpStrictTransportSecurity(
                                                hstsConfig ->
                                                        hstsConfig
                                                                .maxAgeInSeconds(31536000)
                                                                .includeSubDomains(true)))
                // Maintenance filter runs FIRST — short-circuits non-admin /api/v1/* with 503
                // before auth work happens, so a maintenance window doesn't burn DB/Redis time on
                // requests we're going to reject anyway.
                .addFilterBefore(maintenanceFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(rateLimitFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(apiKeyAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
                // OAuth2 login disabled - handlers removed
                .oauth2Login(oauth2 -> oauth2.disable());

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();

        // Set allowed origins from configuration
        configuration.setAllowedOrigins(
                Arrays.asList(appProperties.getCors().getAllowedOrigins().split(",")));

        // Allowed HTTP methods
        configuration.setAllowedMethods(
                Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));

        // Allowed headers
        configuration.setAllowedHeaders(
                Arrays.asList(
                        "Authorization",
                        "Content-Type",
                        "X-Requested-With",
                        "Accept",
                        "X-API-KEY"));

        // Exposed headers
        configuration.setExposedHeaders(
                Arrays.asList("X-RateLimit-Limit", "X-RateLimit-Remaining", "X-RateLimit-Reset"));

        // Allow credentials
        configuration.setAllowCredentials(true);

        // Set max age
        configuration.setMaxAge(3600L);

        // Apply CORS configuration to all paths
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);

        return source;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config)
            throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public UserDetailsService userDetailsService() {
        return customUserDetailsService;
    }

    @Bean
    public SessionRegistry sessionRegistry() {
        return new SessionRegistryImpl();
    }

    @Bean
    public CustomAuthenticationProvider customAuthenticationProvider(
            UserRepository userRepository, PasswordEncoder passwordEncoder) {
        return new CustomAuthenticationProvider(userRepository, passwordEncoder);
    }
}
