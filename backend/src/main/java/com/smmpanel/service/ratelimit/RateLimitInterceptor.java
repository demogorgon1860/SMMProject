package com.smmpanel.service.ratelimit;

import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class RateLimitInterceptor implements HandlerInterceptor {

    private final RateLimitService rateLimitService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String endpoint = request.getRequestURI();
        Long userId = getUserIdFromRequest(request);

        if (userId == null) {
            // Handle unauthenticated requests with IP-based limiting
            String ipAddress = request.getRemoteAddr();
            userId = (long) ipAddress.hashCode();
        }

        if (!rateLimitService.isRequestAllowed(userId, endpoint)) {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.getWriter().write("Rate limit exceeded. Please try again later.");
            return false;
        }

        return true;
    }

    @Override
    public void postHandle(HttpServletRequest request, HttpServletResponse response, Object handler, ModelAndView modelAndView) {
        // Not needed for rate limiting
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        // Not needed for rate limiting
    }

    private Long getUserIdFromRequest(HttpServletRequest request) {
        // Get user ID from security context or session
        // This should match your authentication implementation
        return null; // Replace with actual implementation
    }
}
