package com.aireview.common.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
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

    @Value("${review.parallel.global-ai-concurrency:8}")
    private int globalAiConcurrency;

    @Value("${review.sar.check-concurrency:4}")
    private int sarCheckConcurrency;

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
     * 跨任务的出站调用总闸门。**默认关闭**（{@code global-ai-concurrency <= 0}），
     * 此时各审查任务的并发相互独立：每个任务只受自己的 per-task
     * {@link java.util.concurrent.Semaphore}（大小 = {@code chunk-concurrency}）约束，
     * 不会因为别的任务在跑而被拖慢。
     *
     * <p>为什么默认关闭：之前这里是全任务共享的 8 个公平许可，导致单任务能开 6 并发、
     * 两个任务同时跑却只有 8（而不是 12），三个任务时每个实际只剩约 2.7。更糟的是
     * {@code fair=true} 让后发任务必须排在先发任务已入队的 acquire 之后，表现为
     * "另一个文档一审查，我这个就变慢"。
     *
     * <p>关闭时返回一个 {@code Integer.MAX_VALUE} 许可的非公平信号量而不是 null：
     * 调用方逻辑无需分支，acquire/release 退化成一次 CAS，相对一次 LLM HTTP 调用
     * 的开销可忽略。
     *
     * <p>什么时候该重新打开：上游按账号（而非按任务）限流，多任务并行触发 429 时。
     * 设为正数即可重新设总闸门；此时它是**额外**约束，不改变 per-task 上限。
     * 线程池已按 {@code chunk-concurrency × async.max-pool-size} 配足，
     * 关闭总闸门不会造成线程饥饿。
     */
    @Bean(name = "reviewAiCallSemaphore")
    public Semaphore reviewAiCallSemaphore() {
        if (globalAiConcurrency <= 0) {
            log.info("Global review AI concurrency DISABLED (global-ai-concurrency={}): "
                            + "each task is bounded only by its own chunk-concurrency={}, "
                            + "so tasks no longer throttle each other",
                    globalAiConcurrency, chunkConcurrency);
            return new Semaphore(Integer.MAX_VALUE, false);
        }
        int permits = Math.max(2, globalAiConcurrency);
        log.info("Global review AI concurrency initialized: permits={}, fair=true "
                + "(shared across ALL tasks — tasks will throttle each other)", permits);
        return new Semaphore(permits, true);
    }

    /** SAR（结构化精准审查）路由/分组/复核的并发线程池。与 chunkReviewExecutor 同构、物理隔离。 */
    @Bean(name = "sarCheckExecutor")
    public TaskExecutor sarCheckExecutor() {
        int concurrency = Math.max(1, sarCheckConcurrency);
        int core = Math.max(concurrency * maxPoolSize, concurrency);
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(core);
        executor.setMaxPoolSize(core);
        executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("sar-check-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.setAwaitTerminationSeconds(5);
        executor.initialize();
        log.info("SAR check thread pool initialized: core=max={}, queue=0 "
                + "(checkConcurrency={} × maxTasks={})", core, concurrency, maxPoolSize);
        return executor;
    }
}
