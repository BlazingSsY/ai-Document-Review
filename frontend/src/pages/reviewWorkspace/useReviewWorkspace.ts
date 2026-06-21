import { useCallback, useEffect, useRef, useState } from 'react';
import { Form, message } from 'antd';
import { useNavigate, useParams } from 'react-router-dom';
import type { ReviewMode, ReviewTask } from '../../api/reviews';
import {
  getReviewApi,
  getReviewDetailAnyPipeline,
  getReviewSourcesAnyPipeline,
} from '../../api/pipelineApi';
import taskWebSocket, { TaskProgressMessage } from '../../utils/websocket';
import useLogStore, { LogEntry } from '../../store/logStore';
import {
  extractCheckResults,
  extractIssues,
  findIssueChunk,
  formatTime,
  isHighConfidenceNotApplicable,
  isProblemCheck,
  normalizeStatus,
  numericField,
  recordArray,
  scoreField,
  sourceCandidatesForItem,
  sourceChapterLabel,
  sourcePayload,
  sourceText,
  sourceTitle,
  textField,
} from './helpers';

export function useReviewWorkspace() {
  const { taskId } = useParams<{ taskId: string }>();
  const navigate = useNavigate();
  const [loading, setLoading] = useState(true);
  const [task, setTask] = useState<ReviewTask | null>(null);
  const logs = useLogStore((s) => (taskId ? s.logsByTask[taskId] || [] : []));
  const appendLog = useLogStore((s) => s.appendLog);
  const clearLogs = useLogStore((s) => s.clearLogs);
  const [wsProgress, setWsProgress] = useState<number>(0);
  const [reReviewing, setReReviewing] = useState(false);
  const [retryingFailed, setRetryingFailed] = useState(false);
  const [exporting, setExporting] = useState(false);
  const [exportingAudit, setExportingAudit] = useState(false);
  const [exportingReport, setExportingReport] = useState(false);
  const [manualReviewOpen, setManualReviewOpen] = useState(false);
  const [manualSaving, setManualSaving] = useState(false);
  const [manualTarget, setManualTarget] = useState<Record<string, unknown> | null>(null);
  const [activeIssueIndex, setActiveIssueIndex] = useState(0);
  const [activeSourceIndex, setActiveSourceIndex] = useState(0);
  const [sourcesLoading, setSourcesLoading] = useState(false);
  const [manualForm] = Form.useForm();
  const logEndRef = useRef<HTMLDivElement>(null);

  const addLog = useCallback((level: LogEntry['level'], logMessage: string, progress?: number) => {
    if (!taskId) return;
    appendLog(taskId, { time: formatTime(new Date()), level, message: logMessage, progress });
  }, [taskId, appendLog]);

  const reviewMode: ReviewMode = (task?.reviewMode ?? 'CHUNK') as ReviewMode;
  const reviewApi = getReviewApi(reviewMode);

  const fetchSources = useCallback(async (id: string) => {
    setSourcesLoading(true);
    try {
      const res = await getReviewSourcesAnyPipeline(id);
      const sources = res.data.data;
      setTask((prev) => {
        if (!prev || prev.id !== id || !prev.aiResult) return prev;
        return {
          ...prev,
          aiResult: {
            ...prev.aiResult,
            originalSources: prev.aiResult.originalSources ?? sources?.originalSources ?? [],
            chunkResults: prev.aiResult.chunkResults ?? sources?.chunkResults ?? [],
          },
        };
      });
    } catch {
      // 溯源原文为尽力而为，失败不影响主功能。
    } finally {
      setSourcesLoading(false);
    }
  }, []);

  const fetchDetail = useCallback(async () => {
    if (!taskId) return;
    setLoading(true);
    try {
      const res = await getReviewDetailAnyPipeline(taskId);
      const t = res.data.data;
      setTask(t);
      // 硬刷新场景：用后端返回的最近进度立即点亮进度条，不必干等下一条 WS 帧。
      if (typeof t.progress === 'number' && t.status?.toUpperCase() === 'PROCESSING') {
        setWsProgress((prev) => Math.max(prev, t.progress as number));
      }
      if (t.aiResult && !t.aiResult.originalSources) {
        void fetchSources(taskId);
      }
      const existing = useLogStore.getState().logsByTask[taskId];
      if (!existing || existing.length === 0) {
        const s = t.status?.toUpperCase();
        if (s === 'PENDING') {
          addLog('info', '任务已创建，等待处理...');
        } else if (s === 'PROCESSING') {
          addLog('info', '任务正在处理中，已连接实时日志...');
        } else if (s === 'COMPLETED') {
          addLog('success', '任务已完成');
        } else if (s === 'FAILED') {
          addLog('error', `任务失败：${t.failReason || '未知错误'}`);
        } else if (s === 'CANCELLED') {
          addLog('warning', '任务已被取消');
        }
      }
    } catch {
      addLog('error', '加载任务详情失败');
    } finally {
      setLoading(false);
    }
  }, [taskId, addLog, fetchSources]);

  const goDashboard = useCallback(() => {
    navigate('/dashboard');
  }, [navigate]);

  const handleReReview = async () => {
    if (!taskId) return;
    setReReviewing(true);
    try {
      const res = await reviewApi.reReview(taskId);
      const newId = res.data.data.id;
      message.success('重新审查任务已提交');
      clearLogs(taskId);
      setWsProgress(0);
      setTask(null);
      navigate(`/review/${newId}`, { replace: true });
    } catch {
      message.error('重新审查失败');
    } finally {
      setReReviewing(false);
    }
  };

  const handleRetryFailedChunks = async () => {
    if (!taskId) return;
    setRetryingFailed(true);
    try {
      const res = await reviewApi.retryFailedChunks(taskId);
      setTask(res.data.data);
      setWsProgress(10);
      addLog('info', '开始重新审查失败切片...');
      message.success('失败切片重审已提交');
    } catch {
      message.error('失败切片重审提交失败');
    } finally {
      setRetryingFailed(false);
    }
  };

  const handleExportExcel = async () => {
    if (!taskId) return;
    setExporting(true);
    try {
      const res = await reviewApi.exportReviewExcel(taskId);
      const blob = new Blob([res.data], {
        type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
      });
      const url = window.URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = url;
      link.download = `审查意见_${task?.fileName?.replace(/\.[^/.]+$/, '') || taskId.substring(0, 8)}.xlsx`;
      document.body.appendChild(link);
      link.click();
      document.body.removeChild(link);
      window.URL.revokeObjectURL(url);
      message.success('审查意见表已导出');
    } catch {
      message.error('导出失败');
    } finally {
      setExporting(false);
    }
  };

  const handleExportAudit = async () => {
    if (!taskId) return;
    setExportingAudit(true);
    try {
      const res = await reviewApi.exportReviewAudit(taskId);
      const blob = new Blob([res.data], { type: 'application/json' });
      const url = window.URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = url;
      link.download = `审计日志_${taskId.substring(0, 8)}.json`;
      document.body.appendChild(link);
      link.click();
      document.body.removeChild(link);
      window.URL.revokeObjectURL(url);
      message.success('审计日志已导出');
    } catch {
      message.error('审计日志导出失败');
    } finally {
      setExportingAudit(false);
    }
  };

  const handleExportReport = async () => {
    if (!taskId) return;
    setExportingReport(true);
    try {
      const res = await reviewApi.exportReviewReport(taskId);
      const blob = new Blob([res.data], {
        type: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
      });
      const url = window.URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = url;
      link.download = `审查报告_${task?.fileName?.replace(/\.[^/.]+$/, '') || taskId.substring(0, 8)}.docx`;
      document.body.appendChild(link);
      link.click();
      document.body.removeChild(link);
      window.URL.revokeObjectURL(url);
      message.success('审查报告已导出');
    } catch {
      message.error('审查报告导出失败');
    } finally {
      setExportingReport(false);
    }
  };

  const openManualReview = (item: Record<string, unknown> | null) => {
    if (!item) return;
    setManualTarget(item);
    manualForm.setFieldsValue({
      finalStatus: textField(item, ['manualStatus', 'status']) || 'Review',
      accepted: item.manualAccepted === undefined ? undefined : String(item.manualAccepted),
      comment: textField(item, ['manualComment']),
    });
    setManualReviewOpen(true);
  };

  const closeManualReview = () => {
    setManualReviewOpen(false);
  };

  const handleManualReviewSubmit = async () => {
    if (!taskId || !manualTarget) return;
    const checkCode = textField(manualTarget, ['check_code', 'checkCode']);
    if (!checkCode) {
      message.error('当前检查项缺少编号，无法人工复核');
      return;
    }
    setManualSaving(true);
    try {
      const values = await manualForm.validateFields();
      const res = await reviewApi.updateCheckDecision(taskId, {
        checkCode,
        findingId: textField(manualTarget, ['finding_id', 'findingId']) || undefined,
        sourceChunk: numericField(manualTarget, ['sourceChunk', 'chunk']),
        finalStatus: values.finalStatus,
        accepted: values.accepted === undefined ? undefined : values.accepted === 'true',
        comment: values.comment,
      });
      setTask(res.data.data);
      setManualReviewOpen(false);
      message.success('人工复核已保存');
    } catch {
      message.error('人工复核保存失败');
    } finally {
      setManualSaving(false);
    }
  };

  const handleInlineManualDecision = async (
    item: Record<string, unknown>,
    decision: 'Pass' | 'Fail' | 'Review',
  ) => {
    if (!taskId) return;
    const checkCode = textField(item, ['check_code', 'checkCode']);
    if (!checkCode) {
      message.error('当前检查项缺少编号，无法保存人工校验');
      return;
    }
    setManualSaving(true);
    try {
      const res = await reviewApi.updateCheckDecision(taskId, {
        checkCode,
        findingId: textField(item, ['finding_id', 'findingId']) || undefined,
        sourceChunk: numericField(item, ['sourceChunk', 'chunk']),
        finalStatus: decision,
        comment: decision === 'Review' ? '改判' : '',
      });
      setTask(res.data.data);
      message.success('人工校验已保存');
    } catch {
      message.error('人工校验保存失败');
    } finally {
      setManualSaving(false);
    }
  };

  const selectIssue = (index: number) => {
    setActiveIssueIndex(index);
    setActiveSourceIndex(0);
  };

  useEffect(() => {
    logEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [logs]);

  useEffect(() => {
    if (!taskId) return;

    setWsProgress(0);
    taskWebSocket.connect();

    const handler = (data: TaskProgressMessage) => {
      if (data.progress !== undefined) {
        setWsProgress(data.progress);
      }
      const s = data.status?.toUpperCase();
      if (s === 'COMPLETED' || s === 'FAILED' || s === 'CANCELLED') {
        setTimeout(async () => {
          try {
            const res = await getReviewDetailAnyPipeline(taskId);
            const t = res.data.data;
            setTask(t);
            if (t.aiResult && !t.aiResult.originalSources) {
              void fetchSources(taskId);
            }
          } catch {
            // Ignore refresh failures; the polling entry point can still reload the task.
          }
        }, 500);
      }
    };

    taskWebSocket.subscribe(taskId, handler);
    return () => {
      taskWebSocket.unsubscribe(taskId, handler);
    };
  }, [taskId, fetchSources]);

  useEffect(() => {
    void fetchDetail();
  }, [fetchDetail]);

  useEffect(() => {
    setActiveSourceIndex(0);
  }, [activeIssueIndex]);

  // 仅在切换任务 / 重新审查（导航到新 taskId）时重置选中项；
  // 后台补原文、失败切片重审等 in-place 更新 aiResult 时不打断用户已选的检查项。
  useEffect(() => {
    setActiveIssueIndex(0);
  }, [taskId]);

  const status = normalizeStatus(task?.status || '');
  const issues = extractIssues(task?.aiResult || null);
  const checkResults = extractCheckResults(task?.aiResult || null);
  const hasCheckMatrix = checkResults.length > 0;
  const visibleCheckResults = checkResults.filter((item) => !isHighConfidenceNotApplicable(item));
  const reviewItems = hasCheckMatrix ? visibleCheckResults : issues;
  const problemCount = hasCheckMatrix
    ? visibleCheckResults.filter(isProblemCheck).length
    : issues.length;
  const totalChunks = task?.aiResult?.totalChunks as number | undefined;
  const categoryCounts = (task?.aiResult?.categoryCounts || {}) as Record<string, number>;
  const checkStatusCounts = hasCheckMatrix
    ? visibleCheckResults.reduce<Record<string, number>>((counts, item) => {
        const itemStatus = textField(item, ['status']) || 'Review';
        counts[itemStatus] = (counts[itemStatus] || 0) + 1;
        return counts;
      }, {})
    : {};
  const chunkResults = (task?.aiResult?.chunkResults || []) as Array<Record<string, unknown>>;
  const originalSources = recordArray(task?.aiResult?.originalSources);
  const failedChunks = (task?.aiResult?.failedChunks || []) as Array<Record<string, unknown>>;
  const failedChunkCount = Number(task?.aiResult?.failedChunkCount || failedChunks.length || 0);
  const isProcessing = status === 'processing';
  const activeIndex = reviewItems.length > 0 ? Math.min(activeIssueIndex, reviewItems.length - 1) : -1;
  const activeItem = activeIndex >= 0 ? reviewItems[activeIndex] : null;
  const activeChunk = findIssueChunk(activeItem, chunkResults);
  const sourceCandidates = sourceCandidatesForItem(activeItem, activeChunk, originalSources);
  const activeSourceSafeIndex = sourceCandidates.length > 0
    ? Math.min(activeSourceIndex, sourceCandidates.length - 1)
    : -1;
  const activeSource = activeSourceSafeIndex >= 0 ? sourceCandidates[activeSourceSafeIndex] : sourcePayload(activeChunk);
  const activeSourceText = textField(activeSource, ['text', 'content', 'originalText', 'sourceText'])
    || (originalSources.length === 0 ? sourceText(activeChunk) : '');
  const activeSourceHtml = textField(activeSource, ['html', 'contentHtml']);
  const activeSourceTitle = textField(activeSource, ['sectionPath', 'chapterTitle', 'title']) || sourceTitle(activeChunk);
  const activeSourceId = textField(activeSource, ['sourceId', 'blockId']);
  const activeSourceChapterLabel = sourceChapterLabel(activeSource);
  const activeEvidenceSourceId = textField(activeSource, ['evidenceSourceId']);
  const activeStartNodeId = textField(activeSource, ['startNodeId', 'start_node_id']);
  const activeEndNodeId = textField(activeSource, ['endNodeId', 'end_node_id']);
  const activeSourceReason = textField(activeSource, ['reason']);
  const activeSourceScore = scoreField(activeSource, ['score']);
  const activeSourceLength = numericField(activeSource, ['textLength', 'contentLength']);
  const activeSourceTokens = numericField(activeSource, ['estimatedTokens']);
  const activeLocator = textField(activeItem, ['evidence', 'originalText']);
  const displayedChunkCount = totalChunks ?? (chunkResults.length > 0 ? chunkResults.length : '-');

  return {
    activeEndNodeId,
    activeEvidenceSourceId,
    activeIndex,
    activeItem,
    activeLocator,
    activeSourceChapterLabel,
    activeSourceHtml,
    activeSourceId,
    activeSourceLength,
    activeSourceReason,
    activeSourceSafeIndex,
    activeSourceScore,
    activeSourceText,
    activeSourceTitle,
    activeSourceTokens,
    activeStartNodeId,
    categoryCounts,
    checkStatusCounts,
    chunkResults,
    closeManualReview,
    displayedChunkCount,
    exporting,
    exportingAudit,
    exportingReport,
    failedChunkCount,
    failedChunks,
    goDashboard,
    handleExportAudit,
    handleExportExcel,
    handleExportReport,
    handleInlineManualDecision,
    handleManualReviewSubmit,
    handleReReview,
    handleRetryFailedChunks,
    hasCheckMatrix,
    isProcessing,
    loading,
    logEndRef,
    logs,
    manualForm,
    manualReviewOpen,
    manualSaving,
    openManualReview,
    problemCount,
    reReviewing,
    retryingFailed,
    reviewItems,
    reviewMode,
    selectIssue,
    setActiveSourceIndex,
    sourceCandidates,
    sourcesLoading,
    status,
    task,
    wsProgress,
  };
}

export type ReviewWorkspaceViewModel = ReturnType<typeof useReviewWorkspace>;
