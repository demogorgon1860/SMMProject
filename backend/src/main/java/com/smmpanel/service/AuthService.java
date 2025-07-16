package com.smmpanel.service;

import com.smmpanel.dto.UserDto;
import com.smmpanel.dto.auth.*;
import com.smmpanel.entity.User;
import com.smmpanel.entity.UserRole;
import com.smmpanel.exception.UserNotFoundException;
import com.smmpanel.repository.UserRepository;
import com.smmpanel.security.JwtService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuthenticationManager authenticationManager;

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        // Check if user already exists
        if (userRepository.existsByUsername(request.getUsername())) {
            throw new RuntimeException("Username already exists");
        }
        
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new RuntimeException("Email already registered");
        }

        // Create new user
        User user = new User();
        user.setUsername(request.getUsername());
        user.setEmail(request.getEmail());
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        user.setRole(UserRole.USER);
        user.setApiKey(generateApiKey());
        
        user = userRepository.save(user);
        
        log.info("New user registered: {}", user.getUsername());

        // Generate tokens
        String accessToken = jwtService.generateToken(user.getUsername());
        String refreshToken = jwtService.generateRefreshToken(user.getUsername());

        return AuthResponse.builder()
                .token(accessToken)
                .refreshToken(refreshToken)
                .user(mapToDto(user))
                .build();
    }

    public AuthResponse login(LoginRequest request) {
        authenticationManager.authenticate(
            new UsernamePasswordAuthenticationToken(
                request.getUsername(),
                request.getPassword()
            )
        );

        User user = userRepository.findByUsername(request.getUsername())
                .orElseThrow(() -> new UserNotFoundException("User not found"));

        String accessToken = jwtService.generateToken(user.getUsername());
        String refreshToken = jwtService.generateRefreshToken(user.getUsername());

        return AuthResponse.builder()
                .token(accessToken)
                .refreshToken(refreshToken)
                .user(mapToDto(user))
                .build();
    }

    public UserDto getCurrentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String username = auth.getName();
        
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new UserNotFoundException("User not found"));
                
        return mapToDto(user);
    }

    public TokenResponse refreshToken(RefreshTokenRequest request) {
        String username = jwtService.extractUsername(request.getRefreshToken());
        
        if (username != null && jwtService.isTokenValid(request.getRefreshToken())) {
            String newAccessToken = jwtService.generateToken(username);
            
            return TokenResponse.builder()
                    .accessToken(newAccessToken)
                    .refreshToken(request.getRefreshToken())
                    .build();
        }
        
        throw new RuntimeException("Invalid refresh token");
    }

    private String generateApiKey() {
        return "sk_" + UUID.randomUUID().toString().replace("-", "");
    }

    private UserDto mapToDto(User user) {
        return UserDto.builder()
                .id(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .balance(user.getBalance().toString())
                .role(user.getRole().name())
                .apiKey(user.getApiKey())
                .build();
    }
}