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
            HttpSecurity http, RateLimitFilter rateLimitFilter) throws Exception {
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
                                                // Authentication endpoints (singular - actions)
                                                "/api/v*/auth/**",
                                                "/api/auth/**",

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

                                                // API Documentation
                                                "/v3/api-docs/**",
                                                "/swagger-ui/**",
                                                "/swagger-ui.html",
                                                "/swagger-resources/**",
                                                "/webjars/**",

                                                // Error and debug
                                                "/api/debug/**",
                                                "/error",

                                                // YouTube session setup (temporary public for
                                                // initial cookie capture)
                                                "/api/v*/admin/youtube-session/**")
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
