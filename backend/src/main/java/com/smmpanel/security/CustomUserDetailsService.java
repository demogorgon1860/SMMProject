package com.smmpanel.security;

import com.smmpanel.entity.User;
import com.smmpanel.repository.jpa.UserRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        // Normalize to lowercase to match stored format (User entity @PrePersist lowercases)
        String normalizedUsername = username != null ? username.trim().toLowerCase() : username;
        User user =
                userRepository
                        .findByUsername(normalizedUsername)
                        .orElseThrow(
                                () ->
                                        new UsernameNotFoundException(
                                                "User not found: " + normalizedUsername));

        // Soft-deleted accounts must look like "user not found" to every auth path. Returning
        // a disabled UserDetails would leak the account's prior existence (different error
        // message vs. a never-registered username) and could confuse downstream filters that
        // treat 'disabled' as a recoverable state.
        if (user.isSoftDeleted()) {
            throw new UsernameNotFoundException("User not found: " + normalizedUsername);
        }

        return org.springframework.security.core.userdetails.User.builder()
                .username(user.getUsername())
                .password(user.getPasswordHash())
                .authorities(List.of(new SimpleGrantedAuthority(user.getRole().getAuthority())))
                .accountExpired(false)
                .accountLocked(!user.isActive())
                .credentialsExpired(false)
                .disabled(!user.isActive())
                .build();
    }
}
