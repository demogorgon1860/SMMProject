package com.smmpanel.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * Server Configuration Properties
 */
@Data
@Validated
@ConfigurationProperties(prefix = "server")
public class ServerProperties {
    
    @Min(1)
    private int port = 8080;
    
    private Servlet servlet = new Servlet();
    private Compression compression = new Compression();
    private boolean http2 = true;
    private Tomcat tomcat = new Tomcat();
    private Error error = new Error();
    
    @Data
    public static class Servlet {
        private String contextPath = "/api";
    }
    
    @Data
    public static class Compression {
        private boolean enabled = true;
        private String mimeTypes = "text/html,text/xml,text/plain,text/css,text/javascript,application/javascript,application/json";
        
        @Min(1)
        private int minResponseSize = 1024;
    }
    
    @Data
    public static class Tomcat {
        @Min(1)
        private int connectionTimeout = 20000;
        
        @Min(1)
        private int keepAliveTimeout = 20000;
        
        @Min(1)
        private int maxConnections = 8192;
        
        @Min(1)
        private int maxThreads = 200;
        
        @Min(1)
        private int minSpareThreads = 10;
    }
    
    @Data
    public static class Error {
        private String includeMessage = "always";
        private String includeBindingErrors = "always";
        private String includeStackTrace = "on_trace_param";
    }
} 