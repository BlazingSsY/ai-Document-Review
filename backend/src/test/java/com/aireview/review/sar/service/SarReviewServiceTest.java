package com.aireview.review.sar.service;

import com.aireview.common.websocket.WebSocketService;
import com.aireview.modelconfig.service.AiModelService;
import com.aireview.modelconfig.entity.AiModelConfig;
import com.aireview.review.sar.entity.SarReviewTask;
import com.aireview.review.sar.repository.SarDocumentVectorRepository;
import com.aireview.review.sar.repository.SarReviewAuditLogMapper;
import com.aireview.review.sar.repository.SarReviewTaskMapper;
import com.aireview.rule.repository.RuleCheckMapper;
import com.aireview.rule.repository.RuleMapper;
import com.aireview.rule.repository.SarRuleCheckMapper;
import com.aireview.rule.service.SarRuleService;
import com.aireview.scenario.service.SarScenarioService;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SarReviewServiceTest {

    @Mock private SarReviewTaskMapper taskMapper;
    @Mock private SarReviewAuditLogMapper auditLogMapper;
    @Mock private SarRuleService ruleService;
    @Mock private SarScenarioService scenarioService;
    @Mock private SarRuleCheckMapper ruleCheckMapper;
    @Mock private RuleMapper editableRuleMapper;
    @Mock private RuleCheckMapper editableRuleCheckMapper;
    @Mock private SarDocumentVectorRepository vectorRepository;
    @Mock private AiModelService aiModelService;
    @Mock private WebSocketService webSocketService;

    @InjectMocks private SarReviewService service;
    private SarReviewService asyncProxy;

    @BeforeEach
    void setUp() {
        asyncProxy = mock(SarReviewService.class);
        ReflectionTestUtils.setField(service, "self", asyncProxy);
    }

    @Test
    void reReviewPreservesQualityCheckSetting() {
        SarReviewTask original = task("original", SarReviewTask.STATUS_COMPLETED);
        original.setQualityCheckEnabled(false);
        original.setFileName("outline.docx");
        original.setFilePath("outline.docx");
        original.setScenarioId(11L);
        original.setSelectedModel("qwen");
        when(taskMapper.selectById("original")).thenReturn(original);
        when(taskMapper.insert(any(SarReviewTask.class))).thenAnswer(invocation -> {
            SarReviewTask inserted = invocation.getArgument(0);
            inserted.setId("new-task");
            return 1;
        });
        doNothing().when(asyncProxy).executeReviewAsync("new-task");

        service.reReview("original", 7L);

        ArgumentCaptor<SarReviewTask> captor = ArgumentCaptor.forClass(SarReviewTask.class);
        verify(taskMapper).insert(captor.capture());
        assertThat(captor.getValue().getQualityCheckEnabled()).isFalse();
        verify(asyncProxy).executeReviewAsync("new-task");
    }

    @Test
    void retryRejectsPendingTaskBeforeStartingAnotherWorker() {
        when(taskMapper.selectById("pending")).thenReturn(task("pending", SarReviewTask.STATUS_PENDING));

        assertThatThrownBy(() -> service.retryFailedChunks("pending", 7L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("failed, completed or cancelled");

        verify(asyncProxy, never()).executeReviewAsync(any());
    }

    @Test
    void cancelUsesConditionalStatusTransition() {
        SarReviewTask processing = task("processing", SarReviewTask.STATUS_PROCESSING);
        when(taskMapper.selectById("processing")).thenReturn(processing);
        when(taskMapper.update(any(SarReviewTask.class),
                org.mockito.ArgumentMatchers.<Wrapper<SarReviewTask>>any())).thenReturn(1);

        service.cancelTask("processing", 7L);

        assertThat(processing.getStatus()).isEqualTo(SarReviewTask.STATUS_CANCELLED);
        ArgumentCaptor<SarReviewTask> captor = ArgumentCaptor.forClass(SarReviewTask.class);
        verify(taskMapper).update(captor.capture(),
                org.mockito.ArgumentMatchers.<Wrapper<SarReviewTask>>any());
        assertThat(captor.getValue().getStatus()).isEqualTo(SarReviewTask.STATUS_CANCELLED);
    }

    @Test
    void findingFingerprintSeparatesBatchesAndIsStable() {
        String first = service.stableFindingId(
                "rule-1", "R-Q-C001", "BLOCK-001", "错别字证据", "存在错字", 0);
        String same = service.stableFindingId(
                "rule-1", "R-Q-C001", "BLOCK-001", "错别字证据", "存在错字", 0);
        String otherBatch = service.stableFindingId(
                "rule-1", "R-Q-C001", "BLOCK-009", "另一处错字", "存在错字", 0);

        assertThat(first).isEqualTo(same);
        assertThat(otherBatch).isNotEqualTo(first);
        assertThat(first).startsWith("R-Q-C001#");
    }

    @Test
    void longChapterSamplingIncludesHeadMiddleAndTail() {
        String content = "HEAD" + "a".repeat(900)
                + "MIDDLE" + "b".repeat(900) + "TAIL";

        String sampled = service.sampleEvenWindows(content, 300, 3);

        assertThat(sampled).contains("HEAD", "MIDDLE", "TAIL");
        assertThat(sampled.length()).isLessThan(380);
    }

    @Test
    void partialEvidenceCannotProveMissingContent() {
        assertThat(service.statusForEvidenceCoverage("Fail", false, false)).isEqualTo("Review");
        assertThat(service.statusForEvidenceCoverage("Fail", false, true)).isEqualTo("Fail");
        assertThat(service.statusForEvidenceCoverage("Fail", true, false)).isEqualTo("Fail");
    }

    @Test
    void qualityResponseWithoutResultsIsRejectedInsteadOfSilentlyPassing() throws Exception {
        when(aiModelService.callAiModel(any(AiModelConfig.class), anyString(), anyString(), any()))
                .thenReturn("{}");

        assertThatThrownBy(() -> service.callQualityPrompt(
                "task-1", new AiModelConfig(), "prompt", 1, "quality-schema"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("results array");
    }

    private SarReviewTask task(String id, String status) {
        SarReviewTask task = new SarReviewTask();
        task.setId(id);
        task.setUserId(7L);
        task.setStatus(status);
        return task;
    }
}
