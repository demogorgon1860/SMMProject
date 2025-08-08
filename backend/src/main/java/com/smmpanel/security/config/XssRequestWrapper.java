package com.smmpanel.security.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * XSS Request Wrapper that filters potentially malicious content from HTTP requests.
 * 
 * This wrapper intercepts parameter values, headers, and request body content
 * to remove or neutralize common XSS attack patterns.
 * 
 * Features:
 * - Parameter value filtering
 * - Request body filtering
 * - Header value filtering
 * - Configurable XSS patterns
 * - Logging of filtered content for security monitoring
 */
@Slf4j
public class XssRequestWrapper extends HttpServletRequestWrapper {
    
    private final Pattern[] xssPatterns;
    private byte[] cachedBody;
    private Map<String, String[]> filteredParameterMap;
    
    /**
     * Constructor
     * 
     * @param request The original HTTP request
     * @param xssPatterns Array of regex patterns to filter XSS attempts
     */
    public XssRequestWrapper(HttpServletRequest request, Pattern[] xssPatterns) {
        super(request);
        this.xssPatterns = xssPatterns;
        this.filteredParameterMap = new HashMap<>();
        
        // Pre-filter parameters
        if (request.getParameterMap() != null) {
            for (Map.Entry<String, String[]> entry : request.getParameterMap().entrySet()) {
                String[] originalValues = entry.getValue();
                String[] filteredValues = new String[originalValues.length];
                
                for (int i = 0; i < originalValues.length; i++) {
                    filteredValues[i] = filterXss(originalValues[i]);
                }
                
                this.filteredParameterMap.put(entry.getKey(), filteredValues);
            }
        }
    }
    
    /**
     * Filters XSS patterns from input string
     * 
     * @param input The input string to filter
     * @return Filtered string with XSS patterns removed/neutralized
     */
    private String filterXss(String input) {
        if (input == null) {
            return null;
        }
        
        String filteredInput = input;
        boolean wasFiltered = false;
        
        // Apply all XSS patterns
        for (Pattern pattern : xssPatterns) {
            String beforeFilter = filteredInput;
            filteredInput = pattern.matcher(filteredInput).replaceAll("");
            
            if (!beforeFilter.equals(filteredInput)) {
                wasFiltered = true;
            }
        }
        
        // Additional common XSS neutralization
        filteredInput = filteredInput
            .replaceAll("<", "&lt;")
            .replaceAll(">", "&gt;")
            .replaceAll("\"", "&quot;")
            .replaceAll("'", "&#x27;")
            .replaceAll("/", "&#x2F;");
        
        // Log if content was filtered
        if (wasFiltered || !input.equals(filteredInput)) {
            log.warn("XSS content filtered from request. Original length: {}, Filtered length: {}, Remote address: {}", 
                input.length(), filteredInput.length(), getRemoteAddr());
            log.debug("XSS filtered content (first 100 chars): {}", 
                input.length() > 100 ? input.substring(0, 100) + "..." : input);
        }
        
        return filteredInput;
    }
    
    @Override
    public String getParameter(String parameter) {
        String[] values = getParameterValues(parameter);
        return values != null && values.length > 0 ? values[0] : null;
    }
    
    @Override
    public String[] getParameterValues(String parameter) {
        return filteredParameterMap.get(parameter);
    }
    
    @Override
    public Map<String, String[]> getParameterMap() {
        return new HashMap<>(filteredParameterMap);
    }
    
    @Override
    public String getHeader(String name) {
        String originalValue = super.getHeader(name);
        return filterXss(originalValue);
    }
    
    @Override
    public BufferedReader getReader() throws IOException {
        return new BufferedReader(new InputStreamReader(getInputStream()));
    }
    
    @Override
    public jakarta.servlet.ServletInputStream getInputStream() throws IOException {
        if (cachedBody == null) {
            cacheBody();
        }
        
        return new XssServletInputStream(new ByteArrayInputStream(cachedBody));
    }
    
    /**
     * Cache and filter the request body
     */
    private void cacheBody() throws IOException {
        if (cachedBody != null) {
            return;
        }
        
        // Read the original body
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(super.getInputStream(), StandardCharsets.UTF_8))) {
            String originalBody = reader.lines().collect(Collectors.joining("\n"));
            
            // Filter the body content
            String filteredBody = filterXss(originalBody);
            
            // Cache the filtered body
            cachedBody = filteredBody.getBytes(StandardCharsets.UTF_8);
        }
    }
    
    /**
     * Custom ServletInputStream implementation for filtered content
     */
    private static class XssServletInputStream extends jakarta.servlet.ServletInputStream {
        private final ByteArrayInputStream inputStream;
        
        public XssServletInputStream(ByteArrayInputStream inputStream) {
            this.inputStream = inputStream;
        }
        
        @Override
        public int read() throws IOException {
            return inputStream.read();
        }
        
        @Override
        public boolean isFinished() {
            return inputStream.available() == 0;
        }
        
        @Override
        public boolean isReady() {
            return true;
        }
        
        @Override
        public void setReadListener(jakarta.servlet.ReadListener readListener) {
            // Not implemented for this simple use case
            throw new UnsupportedOperationException("ReadListener not supported");
        }
    }
}