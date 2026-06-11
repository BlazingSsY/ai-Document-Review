package com.aireview.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
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

    @Value("${review.rag.check-concurrency:4}")
    private int ragCheckConcurrency;

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
     * <p>容量按 "任务并发上限 × 单任务切片并发" 配置：池子至少要能让 {@code maxPoolSize}
     * 个并行任务各占满 {@code chunkConcurrency} 个槽位，互不饿死。单任务并发上限由
     * {@code ReviewService} 中的 per-task {@link java.util.concurrent.Semaphore} 在 *父线程*
     * 上 acquire 来约束（不在 worker 里 park），所以每个 submit 进池子的 runnable 都是马上有
     * 活干的，不会把线程白白卡在等许可上。这是修过的 bug：之前 core=4/queue=1000 导致所有
     * 文档共用 4 个线程，任务 2 的切片永远排在任务 1 后面。
     */
    @Bean(name = "chunkReviewExecutor")
    public Executor chunkReviewExecutor() {
        int core = Math.max(chunkConcurrency * maxPoolSize, chunkConcurrency);
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(core);
        executor.setMaxPoolSize(core);
        executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("chunk-review-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.setAwaitTerminationSeconds(5);
        executor.initialize();
        log.info("Chunk review thread pool initialized: core=max={}, queue=0 "
                + "(chunkConcurrency={} × maxTasks={})", core, chunkConcurrency, maxPoolSize);
        return executor;
    }

    /**
     * Dedicated executor for independent RAG checklist items. Keeping this pool
     * separate prevents slow chat-model calls from occupying upload/task threads.
     */
    @Bean(name = "ragCheckExecutor")
    public TaskExecutor ragCheckExecutor() {
        int concurrency = Math.max(1, ragCheckConcurrency);
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(concurrency);
        executor.setMaxPoolSize(concurrency);
        executor.setQueueCapacity(1000);
        executor.setThreadNamePrefix("rag-check-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.setAwaitTerminationSeconds(5);
        executor.initialize();
        log.info("RAG check thread pool initialized: core=max={}, queue=1000", concurrency);
        return executor;
    }
}
