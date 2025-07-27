package com.smmpanel.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Min;

/**
 * Task Configuration Properties
 */
@Data
@Validated
@ConfigurationProperties(prefix = "spring.task")
public class TaskProperties {
    
    private Execution execution = new Execution();
    private Scheduling scheduling = new Scheduling();
    
    @Data
    public static class Execution {
        private Pool pool = new Pool();
        private String threadNamePrefix = "smm-async-";
        
        @Data
        public static class Pool {
            @Min(1)
            private int coreSize = 8;
            
            @Min(1)
            private int maxSize = 20;
            
            @Min(1)
            private int queueCapacity = 1000;
        }
    }
    
    @Data
    public static class Scheduling {
        private Pool pool = new Pool();
        private String threadNamePrefix = "smm-scheduled-";
        
        @Data
        public static class Pool {
            @Min(1)
            private int size = 5;
        }
    }
} 