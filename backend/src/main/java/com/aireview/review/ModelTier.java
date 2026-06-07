package com.aireview.review;

import com.aireview.entity.AiModelConfig;
import com.aireview.review.llm.ThinkingModeDetector;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 模型档位与批处理预算。
 *
 * <p>当前为"方案 A"：后端按 provider + modelKey 正则硬编码档位判定，
 * 用户不可在 UI 上覆盖。未来如需让用户对单个模型调整 batch 预算，
 * 升级到"方案 B"——在 {@code ai_model_config} 表新增
 * {@code effective_user_budget} 与 {@code max_chunks_per_batch} 字段，
 * 让 {@link #detect} 优先读取数据库字段，未配置时再回退到下面的正则判定。
 *
 * <p>档位含义（详见 README "模型档位配置" 一节）：
 * <ul>
 *   <li>PREMIUM — gpt-4o / claude-sonnet / qwen-max 等，user 24K，单批 8 chunk</li>
 *   <li>MID — deepseek-v3 / glm-4 等，user 18K，单批 6 chunk</li>
 *   <li>LIGHT — qwen-turbo / glm-3.5 / 通用兜底，user 10K，单批 4 chunk</li>
 *   <li>THINKING — 任何思维模型（GLM-4.5+ / Kimi-K2.5+ / deepseek-r1 等），user 10K，单批 3 chunk</li>
 * </ul>
 *
 * <p>选择标准：受 max_output_tokens（多数模型 8K）约束 ——
 * 单批 chunk 数 × 单 chunk 输出 (~1K) 必须 ≤ 输出预算。
 * 因此 chunk 数上限是硬限制，user_budget 只是装得下这么多 chunk 的输入空间。
 */
public enum ModelTier {

    PREMIUM(24_000, 8),
    MID(18_000, 6),
    LIGHT(10_000, 4),
    THINKING(10_000, 3);

    private final int userBudgetTokens;
    private final int maxChunksPerBatch;

    ModelTier(int userBudgetTokens, int maxChunksPerBatch) {
        this.userBudgetTokens = userBudgetTokens;
        this.maxChunksPerBatch = maxChunksPerBatch;
    }

    public int userBudgetTokens() { return userBudgetTokens; }
    public int maxChunksPerBatch() { return maxChunksPerBatch; }

    /** 顶级模型识别：当前主流"旗舰"系列。匹配 modelKey 或 modelName 任一即可。 */
    private static final Pattern PREMIUM_PATTERN = Pattern.compile(
            // OpenAI
            "gpt-4o|gpt-4\\.[0-9]|gpt-5|o1|o3|o4"
            // Anthropic
            + "|claude-(?:opus|sonnet)-4|claude-4|claude-3\\.5-sonnet"
            // 通义千问旗舰
            + "|qwen-?max|qwen3-max|qwen2\\.5-max"
            // 月之暗面 / DeepSeek / Moonshot 旗舰
            + "|kimi-?k2(?![.\\-]?(?:thinking|5|6|7|8|9))");

    /** 中档模型识别：主流通用模型。 */
    private static final Pattern MID_PATTERN = Pattern.compile(
            // DeepSeek
            "deepseek-(?:v3|chat|v2\\.5)"
            // 通义中档
            + "|qwen-?plus|qwen3-(?:32b|72b)"
            // GLM 通用版（注意：glm-4.5+ 是思维模型，被 ThinkingModeDetector 拦截）
            + "|glm-4(?![.\\-]?[5-9])"
            // OpenAI 中档
            + "|gpt-4-turbo|gpt-3\\.5-turbo-16k|gpt-4o-mini");

    private ModelTier() {
        this(10_000, 4);
    }

    /**
     * 根据模型配置自动识别档位。
     *
     * <p>判定顺序：思维模型 → 顶级 → 中档 → 兜底 LIGHT。
     * 思维模型有自己的服务器锁参数与采样策略，永远走 THINKING 档。
     */
    public static ModelTier detect(AiModelConfig config) {
        if (config == null) return LIGHT;
        if (ThinkingModeDetector.isThinking(config)) return THINKING;
        String key = pickKey(config);
        if (key.isEmpty()) return LIGHT;
        if (PREMIUM_PATTERN.matcher(key).find()) return PREMIUM;
        if (MID_PATTERN.matcher(key).find()) return MID;
        return LIGHT;
    }

    private static String pickKey(AiModelConfig config) {
        String key = config.getModelKey();
        if (key == null || key.isBlank()) key = config.getModelName();
        return key == null ? "" : key.toLowerCase(Locale.ROOT);
    }
}
