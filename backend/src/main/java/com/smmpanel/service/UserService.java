package com.smmpanel.service;

import com.smmpanel.entity.User;
import com.smmpanel.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;

    public String getUsernameByApiKey(String apiKey) {
        // Assume API key is hashed before lookup; if not, hash it here
        User user = userRepository.findByApiKeyHash(apiKey)
                .orElseThrow(() -> new IllegalArgumentException("Invalid API key"));
        if (!user.isActive()) {
            throw new IllegalArgumentException("User account is disabled");
        }
        return user.getUsername();
    }

    public void validateApiKey(String apiKey) {
        getUsernameByApiKey(apiKey); // Will throw if invalid
    }

    public User getUserByApiKey(String apiKey) {
        return userRepository.findByApiKeyHash(apiKey)
                .orElseThrow(() -> new IllegalArgumentException("Invalid API key"));
    }
} 