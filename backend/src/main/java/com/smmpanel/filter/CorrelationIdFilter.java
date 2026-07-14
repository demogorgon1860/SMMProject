package com.smmpanel.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class CorrelationIdFilter extends HttpFilter {

    public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";
    public static final String CORRELATION_ID_MDC_KEY = "correlationId";
    public static final String REQUEST_URI_MDC_KEY = "requestUri";
    public static final String REQUEST_METHOD_MDC_KEY = "requestMethod";

    @Override
    protected void doFilter(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws IOException, ServletException {

        try {
            String correlationId = getCorrelationIdFromHeader(request);
            MDC.put(CORRELATION_ID_MDC_KEY, correlationId);
            MDC.put(REQUEST_URI_MDC_KEY, request.getRequestURI());
            MDC.put(REQUEST_METHOD_MDC_KEY, request.getMethod());
            response.setHeader(CORRELATION_ID_HEADER, correlationId);
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(CORRELATION_ID_MDC_KEY);
            MDC.remove(REQUEST_URI_MDC_KEY);
            MDC.remove(REQUEST_METHOD_MDC_KEY);
        }
    }

    private String getCorrelationIdFromHeader(final HttpServletRequest request) {
        String correlationId = request.getHeader(CORRELATION_ID_HEADER);
        // The header is client-controlled and gets written into MDC/logs — sanitize it to prevent
        // log injection (embedded newlines/control chars). Accept only a short safe token,
        // otherwise generate our own id.
        if (StringUtils.hasText(correlationId)
                && correlationId.length() <= 64
                && correlationId.matches("[A-Za-z0-9._-]+")) {
            return correlationId;
        }
        return generateCorrelationId();
    }

    private String generateCorrelationId() {
        return UUID.randomUUID().toString();
    }
}
