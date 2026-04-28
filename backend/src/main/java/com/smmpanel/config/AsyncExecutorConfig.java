package com.smmpanel.config;

import java.util.concurrent.ThreadPoolExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Provides the {@code asyncExecutor} bean used by {@link
 * org.springframework.scheduling.annotation.Async @Async("asyncExecutor")} methods —
 * EmailService Resend calls, Telegram notifications, profile exports.
 *
 * <p>Originally lived inside {@code AsyncVideoProcessingConfig} (see commit 4fc76acb).
 * Extracted to its own config when the YouTube/Binom video-processing infrastructure
 * was removed during Task 10 cleanup, so the dead-code sweep didn't accidentally take
 * a live executor with it. Parameters preserved verbatim.
 *
 * <p>{@code @EnableAsync} stays on {@code SmmPanelApplication}.
 */
@Slf4j
@Configuration
public class AsyncExecutorConfig {

    /**
     * General purpose async executor for notifications, emails, etc. Used by
     * {@code @Async("asyncExecutor")} annotations.
     */
    @Bean("asyncExecutor")
    public TaskExecutor asyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

        executor.setCorePoolSize(50);
        executor.setMaxPoolSize(100);
        executor.setQueueCapacity(500);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("Async-");

        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setAllowCoreThreadTimeOut(true);

        executor.initialize();

        log.info("Initialized Async ThreadPoolTaskExecutor: core=50, max=100, queue=500");

        return executor;
    }
}
