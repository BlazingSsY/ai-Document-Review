package com.aireview.common.config;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 审查任务并发隔离回归。
 *
 * <p>要防的回归：跨任务总闸门一旦是有限且公平的，单任务能开 {@code chunk-concurrency} 个并发，
 * 两个任务同时跑却只有总闸门那么多，后发任务还要排在先发任务已入队的 acquire 之后——
 * 表现为「另一个文档一审查，我这个就明显变慢」。
 */
class ReviewConcurrencyIsolationTest {

    private static final int CHUNK_CONCURRENCY = 6;

    private static Semaphore gateFor(int globalAiConcurrency) {
        AsyncConfig config = new AsyncConfig();
        ReflectionTestUtils.setField(config, "globalAiConcurrency", globalAiConcurrency);
        ReflectionTestUtils.setField(config, "chunkConcurrency", CHUNK_CONCURRENCY);
        return config.reviewAiCallSemaphore();
    }

    // ---------- 闸门配置语义 ----------

    @Test
    void zeroOrNegativeDisablesTheCrossTaskGate() {
        assertThat(gateFor(0).availablePermits()).isEqualTo(Integer.MAX_VALUE);
        assertThat(gateFor(-1).availablePermits()).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void positiveValueStillCreatesABoundedFairGate() {
        Semaphore gate = gateFor(8);
        assertThat(gate.availablePermits()).isEqualTo(8);
        assertThat(gate.isFair())
                .as("有限总闸门必须公平，否则某个任务可能长期抢不到许可")
                .isTrue();
    }

    @Test
    void aPositiveValueBelowTwoIsFlooredAtTwo() {
        assertThat(gateFor(1).availablePermits()).isEqualTo(2);
    }

    // ---------- 行为：两个任务是否互相拖慢 ----------

    @Test
    void withGateDisabledTwoTasksEachGetTheirFullConcurrency() throws Exception {
        int reached = peakConcurrentCalls(gateFor(0), /*tasks*/ 2);

        assertThat(reached)
                .as("闸门关闭时，2 个任务应各自拿到 %d 个并发，合计 %d",
                        CHUNK_CONCURRENCY, 2 * CHUNK_CONCURRENCY)
                .isEqualTo(2 * CHUNK_CONCURRENCY);
    }

    @Test
    void withGateAtEightTwoTasksAreThrottledBelowTheirCombinedLimit() throws Exception {
        int reached = peakConcurrentCalls(gateFor(8), /*tasks*/ 2);

        assertThat(reached)
                .as("这就是改之前的行为：2 个任务合计只能到 8，而不是 12")
                .isEqualTo(8);
    }

    /**
     * 模拟 {@code tasks} 个审查任务同时跑：每个任务用自己的 per-task 信号量
     * （对应 {@code taskSlots}，大小 = chunk-concurrency）限制自身并发，
     * 每次出站调用还要过一遍 {@code gate}（对应 reviewAiCallSemaphore）。
     *
     * @return 观测到的并发调用峰值
     */
    private static int peakConcurrentCalls(Semaphore gate, int tasks) throws Exception {
        int workers = tasks * CHUNK_CONCURRENCY;
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        // 让已进入的调用都停在里面，从而观测到真正的并发峰值
        CountDownLatch hold = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(workers);

        try {
            for (int t = 0; t < tasks; t++) {
                Semaphore taskSlots = new Semaphore(CHUNK_CONCURRENCY);
                for (int c = 0; c < CHUNK_CONCURRENCY; c++) {
                    pool.submit(() -> {
                        try {
                            taskSlots.acquire();
                            gate.acquire();
                            int now = inFlight.incrementAndGet();
                            peak.accumulateAndGet(now, Math::max);
                            started.countDown();
                            hold.await(5, TimeUnit.SECONDS);
                            inFlight.decrementAndGet();
                            gate.release();
                            taskSlots.release();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    });
                }
            }
            // 闸门有限时不可能全部就位，等一小会儿取峰值即可
            started.await(1500, TimeUnit.MILLISECONDS);
            return peak.get();
        } finally {
            hold.countDown();
            pool.shutdownNow();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }
    }
}
