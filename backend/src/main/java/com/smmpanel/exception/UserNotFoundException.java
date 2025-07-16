package com.smmpanel.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.NOT_FOUND)
public class UserNotFoundException extends RuntimeException {
    
    public UserNotFoundException(String message) {
        super(message);
    }
    
    public UserNotFoundException(String username, boolean byUsername) {
        super(byUsername ? "User not found with username: " + username : "User not found with email: " + username);
    }
}
