package com.aireview.review.core;

import java.util.Set;

/**
 * 审查类别（业务域）——一条审查任务审的是什么文件。
 *
 * <p>与「审查方式」（CHUNK 全文逐章 / SAR 结构化精准两条管线）是正交的两个维度：
 * 类别回答「审的是什么」，管线回答「用什么方法审」。同一类别可以走任一条管线。
 *
 * <p>目前只开放环境试验大纲审查，试验报告审查与可靠性审查是已规划、尚未实现的类别，
 * 在这里先登记出来，让「可选类别」有唯一定义来源，前端置灰展示的也是同一份清单。
 * 新增类别时只需在这里加常量并放进 {@link #ENABLED}，无需再改校验逻辑。
 */
public final class ReviewCategory {

    /** 环境试验大纲审查。系统此前唯一支持的类别，因此也是存量任务的默认归属。 */
    public static final String ENV_TEST_OUTLINE = "ENV_TEST_OUTLINE";

    /** 试验报告审查（规划中，暂不开放）。 */
    public static final String TEST_REPORT = "TEST_REPORT";

    /** 可靠性审查（规划中，暂不开放）。 */
    public static final String RELIABILITY = "RELIABILITY";

    /** 当前允许提交的类别。规划中的类别不在其中，提交会被拒绝。 */
    public static final Set<String> ENABLED = Set.of(ENV_TEST_OUTLINE);

    private ReviewCategory() {
    }

    /**
     * 归一化前端传来的类别值。
     *
     * <p>空值回落到 {@link #ENV_TEST_OUTLINE} 而不是报错：类别是后加的维度，老客户端和
     * 重审等内部调用可能不带它，而在只有一个类别的当下，这个回落是事实正确的。但传了一个
     * 尚未开放的类别是明确的错误意图，必须拒绝，否则任务会带着一个系统根本不会执行的
     * 类别落库。
     */
    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) return ENV_TEST_OUTLINE;
        String value = raw.trim().toUpperCase();
        if (!ENABLED.contains(value)) {
            throw new IllegalArgumentException("暂不支持该审查类别：" + raw);
        }
        return value;
    }
}
