package com.aireview.review.core;

import com.aireview.document.ChunkUtils;
import com.aireview.rule.engine.RuleDispatcher;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 按"规则签名 + token 预算 + chunk 数硬上限"把切片打包成批，降低每文档 AI 调用次数。
 *
 * <p>装箱算法：
 * <ol>
 *   <li>对每个切片，按其分发到的规则的 {@code rule_code} 升序拼成签名串；</li>
 *   <li>同签名切片放进同一个候选桶（共享 system prompt，prompt 缓存可命中）；</li>
 *   <li>桶内按章节顺序遍历，逐个 chunk 加入当前 batch：
 *     <ul>
 *       <li>当前 batch 累计 token + chunk 的预估 token &gt; {@code userBudget} → 关闭当前 batch，开新 batch；</li>
 *       <li>当前 batch chunk 数 ≥ {@code maxChunksPerBatch} → 关闭当前 batch，开新 batch；</li>
 *       <li>单个 chunk 自身就超过 {@code userBudget} → 该 chunk 独占一批（兜底，避免被丢弃）。</li>
 *     </ul>
 *   </li>
 * </ol>
 *
 * <p>动态调整钩子：调用方可在 plan 之后，根据前几批的实际输出占用率，
 * 通过 {@link #replanRemaining} 重新打包剩余切片，提高/降低 chunk 数上限。
 */
@Slf4j
public final class ChunkBatchPlanner {

    private ChunkBatchPlanner() {}

    /**
     * 一批的打包结果。{@code chunkIndices} 是原 chunks 列表的下标，
     * 后端调度按这个下标取出 ChunkResult / DispatchResult。
     */
    @Data
    public static class BatchPlan {
        private final String batchId;            // batch-001 / batch-002 ...
        private final String signature;          // 规则签名（按 rule_code 升序）
        private final List<Integer> chunkIndices;
        private final int estimatedUserTokens;   // 用户消息部分预估
        private final boolean oversized;         // 单 chunk 超预算的标记
    }

    /**
     * 规划全部切片。
     *
     * @param chunks      原切片列表（按章节顺序）
     * @param dispatches  与 chunks 等长的分发结果
     * @param tier        模型档位（决定 userBudget 与 maxChunksPerBatch）
     * @param adaptiveCapOverride 动态调整后的 chunk 数上限；&lt;= 0 表示按 tier 默认
     */
    public static List<BatchPlan> plan(List<ChunkUtils.ChunkResult> chunks,
                                       List<RuleDispatcher.DispatchResult> dispatches,
                                       ModelTier tier,
                                       int adaptiveCapOverride) {
        if (chunks == null || chunks.isEmpty()) return List.of();
        if (dispatches == null || dispatches.size() != chunks.size()) {
            throw new IllegalArgumentException("dispatches size mismatch with chunks");
        }
        int userBudget = tier.userBudgetTokens();
        int chunkCap = adaptiveCapOverride > 0 ? adaptiveCapOverride : tier.maxChunksPerBatch();

        // 1) 按签名分桶；桶内保留原章节顺序
        Map<String, List<Integer>> bySignature = new LinkedHashMap<>();
        for (int i = 0; i < chunks.size(); i++) {
            String sig = signatureOf(dispatches.get(i));
            bySignature.computeIfAbsent(sig, k -> new ArrayList<>()).add(i);
        }

        // 2) 桶内装箱
        List<BatchPlan> plans = new ArrayList<>();
        int seq = 1;
        for (Map.Entry<String, List<Integer>> entry : bySignature.entrySet()) {
            String sig = entry.getKey();
            List<Integer> idxs = entry.getValue();
            List<Integer> current = new ArrayList<>();
            int currentTokens = 0;
            for (int idx : idxs) {
                ChunkUtils.ChunkResult chunk = chunks.get(idx);
                int tokens = estimateChunkUserTokens(chunk);

                // 单 chunk 自身就超过预算 → 独占一批，标记 oversized
                if (tokens > userBudget) {
                    if (!current.isEmpty()) {
                        plans.add(closeBatch(seq++, sig, current, currentTokens, false));
                        current = new ArrayList<>();
                        currentTokens = 0;
                    }
                    List<Integer> solo = new ArrayList<>();
                    solo.add(idx);
                    plans.add(closeBatch(seq++, sig, solo, tokens, true));
                    continue;
                }

                // 触发关闭：超预算 或 达到 chunk 上限
                if ((currentTokens + tokens > userBudget && !current.isEmpty())
                        || current.size() >= chunkCap) {
                    plans.add(closeBatch(seq++, sig, current, currentTokens, false));
                    current = new ArrayList<>();
                    currentTokens = 0;
                }
                current.add(idx);
                currentTokens += tokens;
            }
            if (!current.isEmpty()) {
                plans.add(closeBatch(seq++, sig, current, currentTokens, false));
            }
        }
        log.info("Planned {} batch(es) for {} chunk(s) at tier={} budget={} cap={}",
                plans.size(), chunks.size(), tier, userBudget, chunkCap);
        return plans;
    }

    /**
     * 动态调整：对"还未开跑"的剩余切片重新规划，使用新的 chunk 数上限。
     * 已经跑完/在跑的 batch 不受影响。
     */
    public static List<BatchPlan> replanRemaining(List<ChunkUtils.ChunkResult> chunks,
                                                   List<RuleDispatcher.DispatchResult> dispatches,
                                                   List<Integer> remainingChunkIndices,
                                                   ModelTier tier,
                                                   int adaptiveCap,
                                                   int seqStart) {
        if (remainingChunkIndices == null || remainingChunkIndices.isEmpty()) return List.of();
        List<ChunkUtils.ChunkResult> subChunks = new ArrayList<>();
        List<RuleDispatcher.DispatchResult> subDispatches = new ArrayList<>();
        for (int idx : remainingChunkIndices) {
            subChunks.add(chunks.get(idx));
            subDispatches.add(dispatches.get(idx));
        }
        List<BatchPlan> raw = plan(subChunks, subDispatches, tier, adaptiveCap);
        // 把 sub-index 翻译回原 index，重新分配 batchId 序号
        List<BatchPlan> rewrapped = new ArrayList<>();
        for (int i = 0; i < raw.size(); i++) {
            BatchPlan p = raw.get(i);
            List<Integer> original = new ArrayList<>();
            for (int subIdx : p.getChunkIndices()) original.add(remainingChunkIndices.get(subIdx));
            rewrapped.add(new BatchPlan(
                    String.format("batch-%03d", seqStart + i),
                    p.getSignature(),
                    original,
                    p.getEstimatedUserTokens(),
                    p.isOversized()));
        }
        return rewrapped;
    }

    /** 把分发结果转成"规则签名"：rule_code 升序拼接，未编号规则放最后。 */
    public static String signatureOf(RuleDispatcher.DispatchResult dispatch) {
        if (dispatch == null) return "";
        List<String> codes = new ArrayList<>();
        for (RuleDispatcher.PreparedRule pr : dispatch.getAppliedRules()) {
            String code = pr.getMetadata() != null ? pr.getMetadata().getRuleCode() : null;
            codes.add(code == null || code.isBlank() ? "ZZ-" + pr.getRule().getRuleName() : code);
        }
        codes.sort(Comparator.naturalOrder());
        return String.join("|", codes);
    }

    /** 预估单 chunk 在 user message 中的 token 占用（含分隔符/标题等小开销）。 */
    public static int estimateChunkUserTokens(ChunkUtils.ChunkResult chunk) {
        int body = chunk.getEstimatedTokens();
        int label = chunk.getLabel() == null ? 0 : ChunkUtils.estimateTokens(chunk.getLabel());
        return body + label + 32; // 32 token 给分隔符/编号
    }

    private static BatchPlan closeBatch(int seq, String signature, List<Integer> idxs,
                                         int tokens, boolean oversized) {
        return new BatchPlan(
                String.format("batch-%03d", seq),
                signature,
                new ArrayList<>(idxs),
                tokens,
                oversized);
    }
}
