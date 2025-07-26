package com.smmpanel.service;

import com.smmpanel.entity.User;
import com.smmpanel.repository.UserRepository;
lombok.RequiredArgsConstructor;
lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;

    public String getUsernameByApiKey(String apiKey) {
        User user = userRepository.findByApiKey(apiKey)
                .orElseThrow(() -> new IllegalArgumentException("Invalid API key"));
        if (!user.getIsActive()) {
            throw new IllegalArgumentException("User account is disabled");
        }
        return user.getUsername();
    }

    public void validateApiKey(String apiKey) {
        getUsernameByApiKey(apiKey); // Will throw if invalid
    }

    public User getUserByApiKey(String apiKey) {
        return userRepository.findByApiKey(apiKey)
                .orElseThrow(() -> new IllegalArgumentException("Invalid API key"));
    }
} 