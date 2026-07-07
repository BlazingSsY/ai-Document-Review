package com.aireview.review.llm;

import com.aireview.modelconfig.entity.AiModelConfig;

import java.util.Locale;

/**
 * Provider-aware URL builder. Lifted verbatim from
 * {@code AiModelService.buildFullApiUrl} so the v2 client behaves identically
 * for the URL-resolution edge cases the old code already handled (Moonshot's
 * "/v1" suffix, custom providers without "/chat/completions", etc.).
 *
 * <p>Kept as a stateless helper so unit tests can exercise it without spinning
 * Spring up. The old method stays in place so legacy callers don't break while
 * the migration is in flight.
 */
public final class EndpointResolver {

    private EndpointResolver() {}

    public static String resolveChatCompletionsUrl(AiModelConfig config) {
        String endpoint = config.getEndpoint();
        if (endpoint == null || endpoint.trim().isEmpty()) {
            throw new IllegalArgumentException("API endpoint cannot be empty");
        }
        String provider = config.getProvider() != null
                ? config.getProvider().toLowerCase(Locale.ROOT) : "openai";

        // 本地模型：用户提供完整地址，原样使用，不补全任何路径。
        if ("local".equals(provider)) {
            String url = endpoint.trim();
            if (url.endsWith("/")) url = url.substring(0, url.length() - 1);
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                url = "http://" + url;
            }
            return url;
        }

        String base = endpoint.trim();
        if (!base.startsWith("http://") && !base.startsWith("https://")) {
            base = "https://" + base;
        }
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }

        // OpenAI 兼容路径补全（moonshot / deepseek / minimax / glm / alibaba / 自定义等统一走此逻辑）。
        if (base.contains("/chat/completions")) return base;
        if (base.endsWith("/v1")) return base + "/chat/completions";
        if (base.contains("/v1/")) return base;
        return base + "/v1/chat/completions";
    }
}
