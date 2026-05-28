package com.aireview.review.llm;

import com.aireview.entity.AiModelConfig;

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
        String base = endpoint.trim();
        if (!base.startsWith("http://") && !base.startsWith("https://")) {
            base = "https://" + base;
        }
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }

        switch (provider) {
            case "openai":
                if (!base.contains("/v1")) return base + "/v1/chat/completions";
                if (base.endsWith("/v1")) return base + "/chat/completions";
                return base;
            case "anthropic":
                if (!base.contains("/v1/messages")) return base + "/v1/messages";
                return base;
            case "moonshot":
            default:
                if (base.contains("/chat/completions")) return base;
                if (base.endsWith("/v1")) return base + "/chat/completions";
                if (base.contains("/v1/")) return base;
                return base + "/v1/chat/completions";
        }
    }
}
