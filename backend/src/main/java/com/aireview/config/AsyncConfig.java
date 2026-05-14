package com.aireview.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

@Slf4j
@Configuration
@EnableAsync
public class AsyncConfig {

    @Value("${async.core-pool-size}")
    private int corePoolSize;

    @Value("${async.max-pool-size}")
    private int maxPoolSize;

    @Value("${async.queue-capacity}")
    private int queueCapacity;

    @Value("${async.thread-name-prefix}")
    private String threadNamePrefix;

    @Value("${review.parallel.chunk-concurrency}")
    private int chunkConcurrency;

    @Bean(name = "reviewTaskExecutor")
    public Executor reviewTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix(threadNamePrefix);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.setAwaitTerminationSeconds(5);
        executor.initialize();
        log.info("Review task thread pool initialized: core={}, max={}, queue={}",
                corePoolSize, maxPoolSize, queueCapacity);
        return executor;
    }

    /**
     * 切片级并行调用 AI 的独立线程池。和 {@link #reviewTaskExecutor()} 分开是为了避免互相饿死：
     * 任务级线程池里每条任务又会向切片池提交 N 个子任务，如果共用同一个池，多文档并发上传时
     * 会形成嵌套调用 → 父任务占满核心线程 → 子任务排队 → 全部死锁。
     *
     * <p>AI 调用以网络等待为主，几乎不消耗 CPU，所以核心数直接设为 {@code chunkConcurrency}，
     * 最大数留出 2 倍冗余以吸收短时突发。队列容量很大是为了让一篇文档的所有切片都能进队，
     * 不会触发 CallerRunsPolicy 退化为串行。
     */
    @Bean(name = "chunkReviewExecutor")
    public Executor chunkReviewExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(chunkConcurrency);
        executor.setMaxPoolSize(Math.max(chunkConcurrency * 2, chunkConcurrency));
        executor.setQueueCapacity(1000);
        executor.setThreadNamePrefix("chunk-review-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.setAwaitTerminationSeconds(5);
        executor.initialize();
        log.info("Chunk review thread pool initialized: core={}, max={}, queue={}",
                chunkConcurrency, executor.getMaxPoolSize(), 1000);
        return executor;
    }
}
