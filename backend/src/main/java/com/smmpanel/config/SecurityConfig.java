package com.smmpanel.config;

import com.smmpanel.repository.jpa.UserRepository;
import com.smmpanel.security.*;
import java.util.Arrays;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
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
                                        "/api/v2/webhooks/**", // Webhook endpoints need to be CSRF
                                        // exempt
                                        "/api/v2/auth/**" // Auth endpoints are stateless
                                        ))
                .sessionManagement(
                        session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(
                        auth ->
                                auth.requestMatchers(
                                                "/api/v2/auth/**",
                                                "/actuator/health",
                                                "/api/v2/webhooks/**",
                                                "/v3/api-docs/**",
                                                "/swagger-ui/**",
                                                "/swagger-ui.html")
                                        .permitAll()
                                        .requestMatchers("/api/v2/admin/**")
                                        .hasRole("ADMIN")
                                        .requestMatchers("/api/v2/operator/**")
                                        .hasAnyRole("OPERATOR", "ADMIN")
                                        .anyRequest()
                                        .authenticated())
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
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

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
