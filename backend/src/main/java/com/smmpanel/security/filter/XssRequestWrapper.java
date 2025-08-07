package com.smmpanel.security.filter;

import org.apache.commons.io.IOUtils;
import org.springframework.web.util.HtmlUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.io.*;
import java.util.regex.Pattern;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;

public class XssRequestWrapper extends HttpServletRequestWrapper {
    
    private final Pattern[] xssPatterns;

    public XssRequestWrapper(HttpServletRequest request, Pattern[] patterns) {
        super(request);
        this.xssPatterns = patterns;
    }

    @Override
    public String[] getParameterValues(String parameter) {
        String[] values = super.getParameterValues(parameter);
        if (values == null) {
            return null;
        }

        int count = values.length;
        String[] encodedValues = new String[count];
        for (int i = 0; i < count; i++) {
            encodedValues[i] = stripXSS(values[i]);
        }

        return encodedValues;
    }

    @Override
    public String getParameter(String parameter) {
        String value = super.getParameter(parameter);
        return stripXSS(value);
    }

    @Override
    public String getHeader(String name) {
        String value = super.getHeader(name);
        return stripXSS(value);
    }

    @Override
    public Map<String, String[]> getParameterMap() {
        Map<String, String[]> paramMap = super.getParameterMap();
        Map<String, String[]> encodedMap = new java.util.HashMap<>();
        
        for (Map.Entry<String, String[]> entry : paramMap.entrySet()) {
            String[] values = entry.getValue();
            String[] encodedValues = new String[values.length];
            
            for (int i = 0; i < values.length; i++) {
                encodedValues[i] = stripXSS(values[i]);
            }
            
            encodedMap.put(entry.getKey(), encodedValues);
        }
        
        return Collections.unmodifiableMap(encodedMap);
    }

    @Override
    public Enumeration<String> getHeaders(String name) {
        Enumeration<String> headers = super.getHeaders(name);
        if (headers == null) {
            return null;
        }

        List<String> safeHeaders = new ArrayList<>();
        while (headers.hasMoreElements()) {
            String header = headers.nextElement();
            safeHeaders.add(stripXSS(header));
        }

        return Collections.enumeration(safeHeaders);
    }

    @Override
    public BufferedReader getReader() throws IOException {
        String content = IOUtils.toString(super.getReader());
        String sanitized = stripXSS(content);
        return new BufferedReader(new StringReader(sanitized));
    }

    private String stripXSS(String value) {
        if (value == null) {
            return null;
        }

        // Basic XSS protection
        value = HtmlUtils.htmlEscape(value);

        // Remove null characters
        value = value.replaceAll("\0", "");

        // Check against known XSS patterns
        for (Pattern pattern : xssPatterns) {
            Matcher matcher = pattern.matcher(value);
            if (matcher.find()) {
                value = matcher.replaceAll("");
            }
        }

        // Additional sanitization
        value = value
            // Remove backslash escape sequences
            .replaceAll("\\\\", "")
            // Remove encoded javascript: and vbscript: protocols
            .replaceAll("(?i)javascript:", "")
            .replaceAll("(?i)vbscript:", "")
            // Remove encoded new lines
            .replaceAll("&#10;", "\n")
            .replaceAll("&#13;", "\r")
            // Remove excessive whitespace
            .replaceAll("\\s+", " ")
            .trim();

        return value;
    }

    private boolean hasXssPayload(String value) {
        if (value == null) {
            return false;
        }

        String lowercaseValue = value.toLowerCase();

        // Check for common XSS vectors
        return lowercaseValue.contains("<script") ||
               lowercaseValue.contains("javascript:") ||
               lowercaseValue.contains("vbscript:") ||
               lowercaseValue.contains("onload=") ||
               lowercaseValue.contains("onerror=") ||
               lowercaseValue.contains("onclick=") ||
               lowercaseValue.contains("onmouseover=") ||
               lowercaseValue.contains("onfocus=") ||
               lowercaseValue.contains("onblur=") ||
               lowercaseValue.matches(".*<.*>.*") ||  // Any HTML tags
               lowercaseValue.matches(".*\\\\x[0-9a-f]{2}.*") ||  // Hex encoding
               lowercaseValue.matches(".*&#x?[0-9a-f]+;.*");  // HTML entities
    }
}

