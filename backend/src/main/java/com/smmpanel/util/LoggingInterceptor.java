package com.smmpanel.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.*;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

@Slf4j
public class LoggingInterceptor implements HandlerInterceptor {

    private final ObjectMapper objectMapper;

    public LoggingInterceptor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(
            HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        // Generate a unique request ID
        String requestId = UUID.randomUUID().toString();
        MDC.put("requestId", requestId);

        if (request instanceof ContentCachingRequestWrapper) {
            ContentCachingRequestWrapper requestWrapper = (ContentCachingRequestWrapper) request;

            Map<String, Object> requestMap = new HashMap<>();
            requestMap.put("method", requestWrapper.getMethod());
            requestMap.put("uri", requestWrapper.getRequestURI());
            requestMap.put("query", requestWrapper.getQueryString());
            requestMap.put("headers", getHeaders(requestWrapper));

            log.info("Request: {}", objectMapper.writeValueAsString(requestMap));
        }

        return true;
    }

    @Override
    public void afterCompletion(
            HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex)
            throws Exception {
        if (response instanceof ContentCachingResponseWrapper) {
            ContentCachingResponseWrapper responseWrapper =
                    (ContentCachingResponseWrapper) response;

            Map<String, Object> responseMap = new HashMap<>();
            responseMap.put("status", responseWrapper.getStatus());
            responseMap.put("headers", getHeaders(responseWrapper));

            log.info("Response: {}", objectMapper.writeValueAsString(responseMap));

            // Reset the response wrapper to ensure the response is written to the client
            responseWrapper.copyBodyToResponse();
        }

        // Clear the MDC context
        MDC.clear();
    }

    private Map<String, String> getHeaders(HttpServletRequest request) {
        return Collections.list(request.getHeaderNames()).stream()
                .collect(
                        Collectors.toMap(
                                headerName -> headerName,
                                request::getHeader,
                                (existing, replacement) -> existing));
    }

    private Map<String, String> getHeaders(HttpServletResponse response) {
        return response.getHeaderNames().stream()
                .collect(
                        Collectors.toMap(
                                headerName -> headerName,
                                response::getHeader,
                                (existing, replacement) -> existing));
    }
}
