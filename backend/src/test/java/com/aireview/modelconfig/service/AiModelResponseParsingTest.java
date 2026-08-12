package com.aireview.modelconfig.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AiModelResponseParsingTest {

    private final AiModelService service = new AiModelService(null);

    @Test
    void keepsFinalContentAndOnlyRecordsReasoningLength() {
        String body = """
                {
                  "choices":[{
                    "finish_reason":"stop",
                    "message":{
                      "reasoning_content":"private chain of thought",
                      "content":"{\\\"summary\\\":\\\"final\\\"}"
                    }
                  }],
                  "usage":{"prompt_tokens":10,"completion_tokens":20,"total_tokens":30}
                }
                """;

        AiModelService.ParsedChatResponse response = service.parseOpenAiResponse(body);

        assertThat(response.content()).isEqualTo("{\"summary\":\"final\"}");
        assertThat(response.content()).doesNotContain("private chain of thought");
        assertThat(response.reasoningLength()).isEqualTo("private chain of thought".length());
        assertThat(response.finishReason()).isEqualTo("stop");
        assertThat(response.totalTokens()).isEqualTo(30);
    }

    @Test
    void neverUsesReasoningAsFinalContent() {
        String body = """
                {"choices":[{"finish_reason":"length","message":{
                  "reasoning_content":"unfinished reasoning","content":""
                }}]}
                """;

        AiModelService.ParsedChatResponse response = service.parseOpenAiResponse(body);

        assertThat(response.content()).isEmpty();
        assertThat(response.reasoningLength()).isGreaterThan(0);
        assertThat(response.finishReason()).isEqualTo("length");
    }
}
