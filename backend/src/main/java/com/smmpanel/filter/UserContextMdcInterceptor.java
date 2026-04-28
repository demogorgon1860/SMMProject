package com.smmpanel.filter;

import com.smmpanel.entity.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Pulls the authenticated principal off the SecurityContext and pushes a couple of identity keys
 * into the SLF4J MDC so every log line emitted while serving the request carries userId/username
 * (which the JSON encoder then surfaces as top-level fields).
 *
 * <p>Runs as a {@link HandlerInterceptor} rather than a Servlet Filter on purpose: by the time
 * {@code preHandle} fires, Spring Security's filter chain has already populated the authentication,
 * so we can safely read it. CorrelationIdFilter already covered correlation id + URI/method up
 * front; this is the second pass that adds identity once we know who's making the call.
 *
 * <p>The keys are removed in {@code afterCompletion} on every request — including failed ones — to
 * avoid bleed-over to the next request on a pooled thread.
 */
@Component
public class UserContextMdcInterceptor implements HandlerInterceptor {

    public static final String USER_ID_MDC_KEY = "userId";
    public static final String USERNAME_MDC_KEY = "username";

    @Override
    public boolean preHandle(
            HttpServletRequest request, HttpServletResponse response, Object handler) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null
                || !auth.isAuthenticated()
                || "anonymousUser".equals(auth.getPrincipal())) {
            return true;
        }
        Object principal = auth.getPrincipal();
        if (principal instanceof User user) {
            if (user.getId() != null) {
                MDC.put(USER_ID_MDC_KEY, user.getId().toString());
            }
            if (user.getUsername() != null) {
                MDC.put(USERNAME_MDC_KEY, user.getUsername());
            }
        } else if (auth.getName() != null) {
            MDC.put(USERNAME_MDC_KEY, auth.getName());
        }
        return true;
    }

    @Override
    public void afterCompletion(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler,
            Exception ex) {
        MDC.remove(USER_ID_MDC_KEY);
        MDC.remove(USERNAME_MDC_KEY);
    }
}
