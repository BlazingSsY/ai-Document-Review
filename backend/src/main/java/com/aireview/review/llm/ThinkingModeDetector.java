package com.aireview.review.llm;

import com.aireview.modelconfig.entity.AiModelConfig;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 判断一个模型是否**无法通过请求参数关闭思考**（固定推理模型）。
 *
 * <p>本系统对所有模型统一请求关闭思考：审查任务要的是严格按 schema 吐结构化 JSON，
 * 而不是深度推理——规则文件已经把判定步骤和 Pass/Fail/Review 判据写死了，思维链收益
 * 有限，代价却是和最终 JSON 抢同一份输出预算，经常导致 {@code content} 为空、只剩
 * {@code reasoning_content}。所以原先那个「思考模式」开关已被移除。
 *
 * <p>但有一类模型关不掉：R1 / Reasoner / *-thinking / OpenAI o 系列。它们没有 disable
 * 参数，请求里发什么都照样出思维链。对这类模型仍需特殊对待：
 * <ul>
 *   <li>省略 {@code temperature}（服务端会强制用自己的值，发了也不生效甚至报错）；</li>
 *   <li>把 {@code max_tokens} 抬高，给思维链留出余量，否则最终 JSON 写不完。</li>
 * </ul>
 *
 * <p>这不是一个用户可配的开关，而是按模型 id 自动识别的兼容性保护，因此不暴露到界面。
 * 全系统只此一处判定，{@code AiModelService}、{@code ReasoningModeAdapter} 与
 * {@code ReviewService} 都从这里取，避免多份正则互相走岔。
 */
public final class ThinkingModeDetector {

    /**
     * 固定推理模型的 id 特征。注意这里**不含** kimi-k2.x / glm-4.5+ / deepseek-v3/v4
     * 这类混合模型——它们支持 enable_thinking / thinking 参数，我们会显式关闭，
     * 关掉之后就是普通模型，不该再省略 temperature 或抬高预算。
     */
    private static final Pattern FIXED_REASONING = Pattern.compile(
            "reasoner"
            + "|thinking"
            + "|deepseek-r1"
            + "|(?:^|[/_-])r1(?:$|[._-])"
            // OpenAI o 系列（o1/o3/o4）只接受 low|medium|high，没有关闭档
            + "|(?:^|[/_-])o[134](?:$|[._-])");

    private ThinkingModeDetector() {}

    /** 该模型是否无论如何都会产出思维链（即关不掉）。 */
    public static boolean reasonsUnconditionally(AiModelConfig config) {
        if (config == null) return false;
        String key = config.getModelKey();
        if (key == null || key.isBlank()) key = config.getModelName();
        return reasonsUnconditionally(key);
    }

    /** 同上，按模型 id 字符串判断。 */
    public static boolean reasonsUnconditionally(String modelKeyOrName) {
        if (modelKeyOrName == null || modelKeyOrName.isBlank()) return false;
        return FIXED_REASONING.matcher(modelKeyOrName.toLowerCase(Locale.ROOT)).find();
    }
}
