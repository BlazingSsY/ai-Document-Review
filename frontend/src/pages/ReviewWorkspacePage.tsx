import { useState, useEffect, useCallback, useRef } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import {
  Button,
  Tag,
  Typography,
  Spin,
  Empty,
  Space,
  Card,
  Form,
  Input,
  Modal,
  Progress,
  Select,
  message,
} from 'antd';
import {
  ArrowLeftOutlined,
  CheckCircleOutlined,
  WarningOutlined,
  CloseCircleOutlined,
  LoadingOutlined,
  InfoCircleOutlined,
  ReloadOutlined,
  DownloadOutlined,
} from '@ant-design/icons';
import type { ReviewTask, ReviewMode } from '../api/reviews';
import {
  getReviewApi, getReviewDetailAnyPipeline, PIPELINE_LABEL, PIPELINE_COLOR,
} from '../api/pipelineApi';
import { STATUS_LABELS } from '../utils/constants';
import taskWebSocket, { TaskProgressMessage } from '../utils/websocket';
import useLogStore, { LogEntry } from '../store/logStore';
import '../styles/reviewWorkspace.css';

const { Title, Text } = Typography;

function extractIssues(aiResult: Record<string, unknown> | null): Array<Record<string, unknown>> {
  if (!aiResult) return [];
  const allIssues = aiResult.allIssues;
  if (Array.isArray(allIssues)) return allIssues;
  return [];
}

function extractCheckResults(aiResult: Record<string, unknown> | null): Array<Record<string, unknown>> {
  if (!aiResult) return [];
  const allCheckResults = aiResult.allCheckResults;
  if (Array.isArray(allCheckResults)) return allCheckResults;
  return [];
}

function normalizeStatus(status: string): string {
  return status?.toLowerCase() || 'pending';
}

function formatTime(date: Date): string {
  return date.toLocaleTimeString('zh-CN', { hour12: false, hour: '2-digit', minute: '2-digit', second: '2-digit' });
}

const LOG_LEVEL_STYLES: Record<string, { color: string }> = {
  info: { color: '#1677ff' },
  error: { color: '#ff4d4f' },
  success: { color: '#52c41a' },
  warning: { color: '#faad14' },
};

const LOG_LEVEL_ICONS: Record<string, React.ReactNode> = {
  info: <InfoCircleOutlined />,
  error: <CloseCircleOutlined />,
  success: <CheckCircleOutlined />,
  warning: <WarningOutlined />,
};

const CHECK_STATUS_LABELS: Record<string, string> = {
  Pass: '通过',
  Partial: '部分通过',
  Fail: '不通过',
  'N/A': '不适用',
  Review: '待复核',
};

function textField(record: Record<string, unknown> | null | undefined, keys: string[]): string {
  if (!record) return '';
  for (const key of keys) {
    const value = record[key];
    if (value !== undefined && value !== null && String(value).trim()) {
      return String(value);
    }
  }
  return '';
}

function numericField(record: Record<string, unknown> | null | undefined, keys: string[]): number | undefined {
  if (!record) return undefined;
  for (const key of keys) {
    const value = Number(record[key]);
    if (Number.isFinite(value) && value > 0) return value;
  }
  return undefined;
}

function scoreField(record: Record<string, unknown> | null | undefined, keys: string[]): number | undefined {
  if (!record) return undefined;
  for (const key of keys) {
    const value = Number(record[key]);
    if (Number.isFinite(value)) return value;
  }
  return undefined;
}

function sourcePayload(chunk: Record<string, unknown> | undefined): Record<string, unknown> | null {
  if (!chunk) return null;
  const source = chunk.source;
  if (source && typeof source === 'object' && !Array.isArray(source)) {
    return source as Record<string, unknown>;
  }
  return chunk;
}

function sourceText(chunk: Record<string, unknown> | undefined): string {
  const source = sourcePayload(chunk);
  return textField(source, ['text', 'content', 'originalText', 'sourceText']);
}

function sourceTitle(chunk: Record<string, unknown> | undefined): string {
  const source = sourcePayload(chunk);
  return textField(source, ['sectionPath', 'chapterTitle', 'title']) || '原文片段';
}

function recordArray(value: unknown): Array<Record<string, unknown>> {
  if (!Array.isArray(value)) return [];
  return value.filter((item): item is Record<string, unknown> => (
    !!item && typeof item === 'object' && !Array.isArray(item)
  ));
}

function sourceRefKey(ref: Record<string, unknown>): string {
  const sourceId = textField(ref, ['sourceId', 'blockId']);
  if (sourceId) return `id:${sourceId}`;
  const chapterIndex = numericField(ref, ['chapterIndex']);
  if (chapterIndex !== undefined) return `chapter:${chapterIndex}`;
  const chunk = numericField(ref, ['chunk', 'sourceChunk']);
  if (chunk !== undefined) return `chunk:${chunk}`;
  return textField(ref, ['sectionPath', 'title', 'sourceTitle']);
}

function sourceCandidateKey(source: Record<string, unknown>): string {
  const sourceId = textField(source, ['sourceId', 'blockId']);
  if (sourceId) return `id:${sourceId}`;
  const chapterIndex = numericField(source, ['chapterIndex']);
  if (chapterIndex !== undefined) return `chapter:${chapterIndex}`;
  const chunk = numericField(source, ['chunk', 'sourceChunk']);
  if (chunk !== undefined) return `chunk:${chunk}`;
  return textField(source, ['sectionPath', 'chapterTitle', 'title']);
}

function sourceRefsForItem(
  item: Record<string, unknown> | null,
  chunk: Record<string, unknown> | undefined,
): Array<Record<string, unknown>> {
  const refs = [
    ...recordArray(item?.sourceRefs),
    ...recordArray(chunk?.sourceRefs),
  ];
  const chunkNo = numericField(item, ['sourceChunk', 'chunk'])
    ?? numericField(chunk, ['chunk', 'sourceChunk']);
  if (chunkNo !== undefined) {
    refs.push({
      sourceId: `CHUNK-${String(chunkNo).padStart(3, '0')}`,
      chunk: chunkNo,
      title: textField(item, ['sourceTitle', 'location']) || sourceTitle(chunk),
    });
  }
  const seen = new Set<string>();
  return refs.filter((ref) => {
    const key = sourceRefKey(ref);
    if (!key || seen.has(key)) return false;
    seen.add(key);
    return true;
  });
}

function matchOriginalSource(
  ref: Record<string, unknown>,
  originalSources: Array<Record<string, unknown>>,
): Record<string, unknown> | undefined {
  const sourceId = textField(ref, ['sourceId', 'blockId']);
  if (sourceId) {
    const byId = originalSources.find((source) => textField(source, ['sourceId', 'blockId']) === sourceId);
    if (byId) return byId;
  }

  const chapterIndex = numericField(ref, ['chapterIndex']);
  if (chapterIndex !== undefined) {
    const byChapter = originalSources.find(
      (source) => numericField(source, ['chapterIndex']) === chapterIndex,
    );
    if (byChapter) return byChapter;
  }

  const chunkNo = numericField(ref, ['chunk', 'sourceChunk']);
  if (chunkNo !== undefined) {
    const byChunk = originalSources.find((source) => numericField(source, ['chunk', 'sourceChunk']) === chunkNo);
    if (byChunk) return byChunk;
  }

  const hint = textField(ref, ['sectionPath', 'title', 'sourceTitle', 'location']).toLowerCase();
  if (hint) {
    return originalSources.find((source) => {
      const title = textField(source, ['sectionPath', 'chapterTitle', 'title']).toLowerCase();
      return title && (hint.includes(title) || title.includes(hint));
    });
  }
  return undefined;
}

function sourceCandidatesForItem(
  item: Record<string, unknown> | null,
  chunk: Record<string, unknown> | undefined,
  originalSources: Array<Record<string, unknown>>,
): Array<Record<string, unknown>> {
  const candidates: Array<Record<string, unknown>> = [];
  for (const ref of sourceRefsForItem(item, chunk)) {
    const source = matchOriginalSource(ref, originalSources);
    if (source) {
      candidates.push({
        ...source,
        evidenceSourceId: textField(ref, ['sourceId', 'blockId']),
        startNodeId: textField(ref, ['startNodeId', 'start_node_id']),
        endNodeId: textField(ref, ['endNodeId', 'end_node_id']),
        reason: textField(ref, ['reason']),
        score: scoreField(ref, ['score']),
      });
    }
  }

  if (candidates.length === 0 && originalSources.length > 0) {
    const hint = textField(item, ['sourceTitle', 'location', 'evidence', 'originalText']).toLowerCase();
    if (hint) {
      candidates.push(...originalSources.filter((source) => {
        const title = textField(source, ['sectionPath', 'chapterTitle', 'title']).toLowerCase();
        return title && (hint.includes(title) || title.includes(hint));
      }).slice(0, 5));
    }
  }

  if (candidates.length === 0 && originalSources.length === 0) {
    const fallback = sourcePayload(chunk);
    if (fallback) candidates.push(fallback);
  }

  const seen = new Set<string>();
  return candidates.filter((source) => {
    const key = sourceCandidateKey(source);
    if (!key || seen.has(key)) return false;
    seen.add(key);
    return true;
  });
}

function findIssueChunk(
  issue: Record<string, unknown> | null,
  chunks: Array<Record<string, unknown>>,
): Record<string, unknown> | undefined {
  if (!chunks.length) return undefined;
  if (!issue) return chunks[0];

  const chunkNo = numericField(issue, ['sourceChunk', 'chunk', 'chunkNo', 'chunkIndex']);
  if (chunkNo !== undefined) {
    const byNo = chunks.find((chunk) => Number(chunk.chunk) === chunkNo);
    if (byNo) return byNo;
  }

  const hint = textField(issue, ['sourceTitle', 'chapterTitle', 'location', 'originalText']);
  const normalizedHint = hint.toLowerCase();
  if (normalizedHint) {
    const byTitle = chunks.find((chunk) => {
      const title = sourceTitle(chunk).toLowerCase();
      return title && (normalizedHint.includes(title) || title.includes(normalizedHint));
    });
    if (byTitle) return byTitle;
  }

  return chunks[0];
}

function checkStatusColor(status: string): string {
  if (status === 'Pass') return 'green';
  if (status === 'Partial') return 'orange';
  if (status === 'Fail') return 'red';
  if (status === 'Review') return 'purple';
  return 'default';
}

function isProblemCheck(item: Record<string, unknown>): boolean {
  const status = textField(item, ['manualStatus', 'status']);
  return status !== 'Pass' && status !== 'N/A';
}

function isHighConfidenceNotApplicable(item: Record<string, unknown>): boolean {
  const status = textField(item, ['status']).trim().toUpperCase();
  const confidence = textField(item, ['confidence']).trim().toLowerCase();
  return status === 'N/A' && confidence === 'high';
}

function confidenceLabel(confidence: string): string {
  if (confidence === 'high') return '高置信度';
  if (confidence === 'medium') return '中置信度';
  if (confidence === 'low') return '低置信度';
  if (confidence === 'single') return '单次判定';
  return '需人工校验';
}

function sourceReasonLabel(reason: string): string {
  if (reason === 'reranker') return '重排命中';
  if (reason === 'vector') return '向量召回';
  if (reason === 'matched_chunk') return '切片匹配';
  if (reason === 'referenced_chapter') return '引用章节';
  return reason || '来源匹配';
}

function sourceChapterLabel(source: Record<string, unknown> | null | undefined): string {
  const title = textField(source, ['sectionPath', 'chapterTitle', 'title']);
  const titleMatch = title.match(/^\s*(?:第\s*)?([0-9]+(?:\.[0-9]+)*|[一二三四五六七八九十百千万零〇]+)\s*(?:章|节|条|款|部分|[、.．\s]|$)/);
  if (titleMatch) return `章节 ${titleMatch[1]}`;
  const chapterIndex = numericField(source, ['chapterIndex']);
  if (chapterIndex !== undefined) return `章节 ${chapterIndex}`;
  const chunk = numericField(source, ['chunk', 'sourceChunk']);
  if (chunk !== undefined) return `切片 ${chunk}`;
  return '';
}

const ALLOWED_SOURCE_TAGS = new Set([
  'DIV', 'P', 'H1', 'H2', 'H3', 'H4', 'H5', 'H6',
  'TABLE', 'THEAD', 'TBODY', 'TR', 'TH', 'TD',
  'BR', 'STRONG', 'EM', 'UL', 'OL', 'LI',
]);

function locatorCandidates(locator: string): string[] {
  const normalized = locator.trim();
  if (!normalized) return [];
  return [
    normalized,
    ...normalized
      .split(/\r?\n|[。；;]/)
      .map((part) => part.replace(/^[“"'‘’]+|[”"'‘’]+$/g, '').trim())
      .filter((part) => part.length >= 8),
  ].sort((left, right) => right.length - left.length);
}

function normalizeLocatorText(value: string): string {
  return value
    .replace(/\|?\s*:?-{3,}:?\s*(?=\||$)/g, ' ')
    .replace(/[|`*_#>]/g, ' ')
    .replace(/\s+/g, '')
    .trim();
}

function buildHighlightedSourceHtml(
  html: string,
  startNodeId: string,
  endNodeId: string,
  locator: string,
): string {
  if (!html || typeof DOMParser === 'undefined') return '';
  const documentNode = new DOMParser().parseFromString(`<div id="source-root">${html}</div>`, 'text/html');
  const root = documentNode.getElementById('source-root');
  if (!root) return '';

  for (const element of Array.from(root.querySelectorAll('*'))) {
    if (!ALLOWED_SOURCE_TAGS.has(element.tagName)) {
      element.replaceWith(documentNode.createTextNode(element.textContent || ''));
      continue;
    }
    for (const attribute of Array.from(element.attributes)) {
      const allowed = ['id', 'class', 'data-node-id', 'data-node-type', 'rowspan', 'colspan', 'border'];
      if (!allowed.includes(attribute.name)) {
        element.removeAttribute(attribute.name);
      }
    }
  }

  const nodes = Array.from(root.querySelectorAll<HTMLElement>('[data-node-id]'));
  let startIndex = startNodeId
    ? nodes.findIndex((node) => node.dataset.nodeId === startNodeId)
    : -1;
  let endIndex = endNodeId
    ? nodes.findIndex((node) => node.dataset.nodeId === endNodeId)
    : startIndex;

  if (startIndex < 0) {
    const candidates = locatorCandidates(locator);
    startIndex = nodes.findIndex((node) => {
      const nodeText = (node.textContent || '').replace(/\s+/g, ' ').trim();
      const compactNodeText = normalizeLocatorText(nodeText);
      return candidates.some((candidate) => {
        const value = candidate.replace(/\s+/g, ' ').trim();
        const compactValue = normalizeLocatorText(value);
        return nodeText.includes(value)
          || (nodeText.length >= 8 && value.includes(nodeText))
          || (compactValue.length >= 4 && compactNodeText.includes(compactValue))
          || (compactNodeText.length >= 8 && compactValue.includes(compactNodeText));
      });
    });
    endIndex = startIndex;
  }

  if (startIndex >= 0) {
    if (endIndex < startIndex) endIndex = startIndex;
    for (let index = startIndex; index <= Math.min(endIndex, nodes.length - 1); index += 1) {
      nodes[index].classList.add('source-node-highlight');
    }
    nodes[startIndex].classList.add('source-node-highlight-start');
  }
  return root.innerHTML;
}

function StructuredSourceView({
  html,
  startNodeId,
  endNodeId,
  locator,
}: {
  html: string;
  startNodeId: string;
  endNodeId: string;
  locator: string;
}) {
  const containerRef = useRef<HTMLDivElement>(null);
  const highlightedHtml = buildHighlightedSourceHtml(html, startNodeId, endNodeId, locator);

  useEffect(() => {
    const highlighted = containerRef.current?.querySelector('.source-node-highlight-start');
    highlighted?.scrollIntoView({ block: 'center', behavior: 'smooth' });
  }, [highlightedHtml]);

  return (
    <div
      ref={containerRef}
      className="source-html-view"
      dangerouslySetInnerHTML={{ __html: highlightedHtml }}
    />
  );
}

function highlightPlainText(text: string, locator: string): React.ReactNode {
  for (const candidate of locatorCandidates(locator)) {
    const index = text.indexOf(candidate);
    if (index >= 0) {
      return (
        <>
          {text.slice(0, index)}
          <mark>{candidate}</mark>
          {text.slice(index + candidate.length)}
        </>
      );
    }
  }
  return text;
}

function ReviewWorkspacePage() {
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
  const [manualForm] = Form.useForm();
  const logEndRef = useRef<HTMLDivElement>(null);

  const addLog = useCallback((level: LogEntry['level'], logMessage: string, progress?: number) => {
    if (!taskId) return;
    appendLog(taskId, { time: formatTime(new Date()), level, message: logMessage, progress });
  }, [taskId, appendLog]);

  // task.reviewMode 决定后续所有详情/导出/复核接口走哪条管线。
  // 历史数据可能没有这个字段——保底当作 CHUNK 处理。
  const reviewMode: ReviewMode = (task?.reviewMode ?? 'CHUNK') as ReviewMode;
  const reviewApi = getReviewApi(reviewMode);

  const fetchDetail = useCallback(async () => {
    if (!taskId) return;
    setLoading(true);
    try {
      const res = await getReviewDetailAnyPipeline(taskId);
      const t = res.data.data;
      setTask(t);
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
  }, [taskId, addLog]);

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
            // Use the unified dispatch endpoint — this WS handler fires before we
            // know whether the task is RAG or CHUNK on the very first load.
            const res = await getReviewDetailAnyPipeline(taskId);
            setTask(res.data.data);
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
  }, [taskId]);

  useEffect(() => {
    fetchDetail();
  }, [fetchDetail]);

  useEffect(() => {
    setActiveSourceIndex(0);
  }, [activeIssueIndex]);

  useEffect(() => {
    setActiveIssueIndex(0);
  }, [taskId, task?.aiResult]);

  if (loading) {
    return (
      <div className="review-loading">
        <Spin size="large" tip="加载中..." />
      </div>
    );
  }

  if (!task) {
    return (
      <Empty description="未找到审查任务" style={{ marginTop: 100 }}>
        <Button type="primary" onClick={() => navigate('/dashboard')}>
          返回工作台
        </Button>
      </Empty>
    );
  }

  const status = normalizeStatus(task.status);
  const issues = extractIssues(task.aiResult);
  const checkResults = extractCheckResults(task.aiResult);
  const hasCheckMatrix = checkResults.length > 0;
  const visibleCheckResults = checkResults.filter((item) => !isHighConfidenceNotApplicable(item));
  const reviewItems = hasCheckMatrix ? visibleCheckResults : issues;
  const problemCount = hasCheckMatrix
    ? visibleCheckResults.filter(isProblemCheck).length
    : issues.length;
  const overallScore = task.aiResult?.overallScore as number | undefined;
  const totalChunks = task.aiResult?.totalChunks as number | undefined;
  const categoryCounts = (task.aiResult?.categoryCounts || {}) as Record<string, number>;
  const checkStatusCounts = hasCheckMatrix
    ? visibleCheckResults.reduce<Record<string, number>>((counts, item) => {
        const itemStatus = textField(item, ['status']) || 'Review';
        counts[itemStatus] = (counts[itemStatus] || 0) + 1;
        return counts;
      }, {})
    : {};
  const chunkResults = (task.aiResult?.chunkResults || []) as Array<Record<string, unknown>>;
  const originalSources = recordArray(task.aiResult?.originalSources);
  const failedChunks = (task.aiResult?.failedChunks || []) as Array<Record<string, unknown>>;
  const failedChunkCount = Number(task.aiResult?.failedChunkCount || failedChunks.length || 0);
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

  return (
    <div className="review-page">
      <div className="page-header review-page-header">
        <Space>
          <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/dashboard')}>
            返回
          </Button>
          <Title level={5} style={{ margin: 0 }}>{task.fileName}</Title>
          <Tag color={PIPELINE_COLOR[reviewMode]}>{PIPELINE_LABEL[reviewMode]}</Tag>
          <Tag color={status === 'completed' ? 'success' : status === 'failed' ? 'error' : status === 'cancelled' ? 'warning' : 'processing'}>
            {STATUS_LABELS[status] || task.status}
          </Tag>
        </Space>
        <Space wrap>
          <Text type="secondary">模型：{task.selectedModel}</Text>
          {status === 'completed' && task.aiResult && (
            <Button icon={<DownloadOutlined />} loading={exporting} onClick={handleExportExcel}>
              导出Excel
            </Button>
          )}
          {status === 'completed' && task.aiResult && (
            <Button icon={<DownloadOutlined />} loading={exportingReport} onClick={handleExportReport}>
              导出报告
            </Button>
          )}
          {status === 'completed' && task.aiResult && (
            <Button icon={<DownloadOutlined />} loading={exportingAudit} onClick={handleExportAudit}>
              导出审计
            </Button>
          )}
          {status === 'completed' && failedChunkCount > 0 && (
            <Button icon={<ReloadOutlined />} loading={retryingFailed} onClick={handleRetryFailedChunks}>
              重审失败切片
            </Button>
          )}
          {!isProcessing && (
            <Button type="primary" icon={<ReloadOutlined />} loading={reReviewing} onClick={handleReReview}>
              重新审查
            </Button>
          )}
        </Space>
      </div>

      <div className="review-workspace">
        <section className="review-overview-strip" aria-label="审查概要">
          <div className="overview-item">
            <span className="overview-label">综合评分</span>
            <strong>{overallScore !== undefined ? overallScore : '-'}</strong>
          </div>
          <div className="overview-item">
            <span className="overview-label">问题数</span>
            <strong className={problemCount > 0 ? 'danger-text' : undefined}>{problemCount}</strong>
          </div>
          <div className="overview-item">
            <span className="overview-label">文档切片</span>
            <strong>{displayedChunkCount}</strong>
          </div>
          <div className="overview-item">
            <span className="overview-label">失败切片</span>
            <strong className={failedChunkCount > 0 ? 'danger-text' : undefined}>{failedChunkCount}</strong>
          </div>
          <div className="overview-tags">
            {hasCheckMatrix && Object.entries(checkStatusCounts).map(([name, count]) => (
              count > 0 ? <Tag key={name} color={checkStatusColor(name)}>{CHECK_STATUS_LABELS[name] || name} {count}</Tag> : null
            ))}
            {Object.entries(categoryCounts).slice(0, 6).map(([name, count]) => (
              <Tag key={name}>{name} {count}</Tag>
            ))}
          </div>
        </section>

        {isProcessing && wsProgress > 0 && (
          <div className="review-progress-line">
            <LoadingOutlined spin />
            <Progress percent={wsProgress} status="active" showInfo={false} />
            <Text type="secondary">{wsProgress}%</Text>
          </div>
        )}

        <section className="review-main-grid">
          <div className="review-results-panel">
            <div className="panel-header">
              <div>
                <h3>{hasCheckMatrix ? '检查项判定矩阵' : '大模型审查结果'}</h3>
                <Text type="secondary">
                  {hasCheckMatrix
                    ? '点击检查项查看原文。判定仅为通过 / 不通过 / 待复核三种；证据不足、部分满足或不适用均判为待复核，交人工复核。'
                    : '点击任一问题，右侧定位并显示完整章节原文。'}
                </Text>
              </div>
              <Tag>{reviewItems.length} 条</Tag>
            </div>

            <div className="findings-list">
              {reviewItems.length === 0 ? (
                <div className="empty-panel">
                  {isProcessing ? (
                    <>
                      <Spin />
                      <Text type="secondary">AI 正在审查中，日志见页面底部。</Text>
                    </>
                  ) : task.aiResult ? (
                    <Empty description={hasCheckMatrix ? '暂无检查项判定' : '未发现审查问题'} />
                  ) : (
                    <Empty description="暂无审查结果" />
                  )}
                </div>
              ) : (
                reviewItems.map((item, idx) => {
                  const statusValue = textField(item, ['status']);
                  const manualStatus = textField(item, ['manualStatus']);
                  const category = textField(item, ['category']);
                  const description = hasCheckMatrix
                    ? textField(item, ['check_question', 'question', 'description'])
                    : textField(item, ['description', 'explanation', 'issue', 'problem', 'summary']);
                  const locator = textField(item, ['evidence', 'originalText']);
                  const reason = textField(item, ['reason']);
                  const suggestion = textField(item, ['suggestion', 'recommendation']);
                  const rule = textField(item, ['ruleName', 'rule_name', 'rule']);
                  const ruleCode = textField(item, ['rule_code', 'ruleCode']);
                  const checkCode = textField(item, ['check_code', 'checkCode']);
                  const confidence = textField(item, ['confidence']) || 'single';
                  const manualDecisionValue = manualStatus === 'Pass' || manualStatus === 'Fail' || manualStatus === 'Review'
                    ? manualStatus
                    : undefined;
                  const confidenceColor = confidence === 'high'
                    ? 'green'
                    : confidence === 'medium'
                      ? 'blue'
                      : confidence === 'low'
                        ? 'orange'
                        : confidence === 'needs_review'
                          ? 'purple'
                          : 'default';
                  const needsManualCheck = confidence === 'needs_review';
                  // Show the 人工校验 dropdown on every check-matrix item (通过/不通过/待复核)
                  // so reviewers can override the AI when its accuracy is unreliable, plus any
                  // non-matrix item flagged needs_review. Requires a check_code to persist.
                  const showManualDecision = needsManualCheck || hasCheckMatrix;
                  const sourceChunkNo = numericField(item, ['sourceChunk', 'chunk']);
                  const missingItems = Array.isArray(item.missing_items) ? item.missing_items : [];
                  const active = idx === activeIndex;
                  const statusClass = statusValue.replace(/[^A-Za-z0-9_-]/g, '-');

                  return (
                    <div
                      key={idx}
                      role="button"
                      tabIndex={0}
                      className={`finding-card ${hasCheckMatrix ? `check-status-${statusClass || 'Review'}` : 'finding-card-neutral'} ${active ? 'active' : ''}`}
                      onClick={() => {
                        setActiveIssueIndex(idx);
                        setActiveSourceIndex(0);
                      }}
                      onKeyDown={(event) => {
                        if (event.key === 'Enter' || event.key === ' ') {
                          event.preventDefault();
                          setActiveIssueIndex(idx);
                          setActiveSourceIndex(0);
                        }
                      }}
                    >
                      <div className="finding-card-top">
                        <Space wrap size={4}>
                          {hasCheckMatrix && (
                            <Tag color={checkStatusColor(statusValue)}>{CHECK_STATUS_LABELS[statusValue] || statusValue || '待复核'}</Tag>
                          )}
                          {category && <Tag>{category}</Tag>}
                          {hasCheckMatrix && rule && <Tag color="blue">{rule}</Tag>}
                          {!hasCheckMatrix && ruleCode && <Tag color="blue">{ruleCode}</Tag>}
                          {!hasCheckMatrix && checkCode && <Tag color="cyan">{checkCode}</Tag>}
                          {manualStatus && <Tag color={checkStatusColor(manualStatus)}>人工：{CHECK_STATUS_LABELS[manualStatus] || manualStatus}</Tag>}
                          {!hasCheckMatrix && sourceChunkNo && <Tag color="purple">切片 {sourceChunkNo}</Tag>}
                        </Space>
                        <div
                          className="confidence-actions"
                          onClick={(event) => event.stopPropagation()}
                          onMouseDown={(event) => event.stopPropagation()}
                        >
                          {!needsManualCheck && (
                            <Tag color={confidenceColor}>{confidenceLabel(confidence)}</Tag>
                          )}
                          {showManualDecision && (
                            <>
                              <Tag color="purple">人工校验</Tag>
                              <Select
                                size="small"
                                className="manual-decision-select"
                                placeholder="处理"
                                value={manualDecisionValue}
                                loading={manualSaving}
                                disabled={manualSaving}
                                onChange={(value: 'Pass' | 'Fail' | 'Review') => handleInlineManualDecision(item, value)}
                                options={[
                                  { label: '通过', value: 'Pass' },
                                  { label: '不通过', value: 'Fail' },
                                  { label: '改判', value: 'Review' },
                                ]}
                              />
                            </>
                          )}
                        </div>
                      </div>
                      {locator && (
                        <div className="finding-locator">
                          <span>判定依据 / 定位线索</span>
                          <p>{locator}</p>
                        </div>
                      )}
                      {!hasCheckMatrix && description && <div className="finding-description">{description}</div>}
                      {reason && (
                        <div className="finding-field">
                          <span>判定理由</span>
                          <p>{reason}</p>
                        </div>
                      )}
                      {!hasCheckMatrix && missingItems.length > 0 && (
                        <div className="finding-field missing">
                          <span>缺失项</span>
                          <p>{missingItems.map((item) => String(item)).join('；')}</p>
                        </div>
                      )}
                      {suggestion && (
                        <div className="finding-field suggestion">
                          <span>建议</span>
                          <p>{suggestion}</p>
                        </div>
                      )}
                      {!hasCheckMatrix && rule && rule !== ruleCode && <div className="rule-name">{rule}</div>}
                    </div>
                  );
                })
              )}
            </div>
          </div>

          <div className="review-source-panel">
            <div className="panel-header">
              <div>
                <h3>对应原文</h3>
                <Text type="secondary">{activeSourceTitle || '完整章节原文'}</Text>
              </div>
              <Space wrap size={4}>
                {(activeSourceChapterLabel || activeSourceId) && (
                  <Tag color="blue">{activeSourceChapterLabel || activeSourceId}</Tag>
                )}
                {activeSourceLength !== undefined && <Tag>{activeSourceLength} 字</Tag>}
                {activeSourceTokens !== undefined && <Tag>{activeSourceTokens} tokens</Tag>}
                {hasCheckMatrix && activeItem && (
                  <Button size="small" type="primary" onClick={() => openManualReview(activeItem)}>
                    人工复核
                  </Button>
                )}
              </Space>
            </div>

            <div className="source-content">
              {sourceCandidates.length > 1 && (
                <div className="source-switcher">
                  <Text type="secondary">关联章节</Text>
                  <Space wrap size={6}>
                    {sourceCandidates.map((source, idx) => (
                      <Button
                        key={sourceCandidateKey(source) || idx}
                        size="small"
                        type={idx === activeSourceSafeIndex ? 'primary' : 'default'}
                        onClick={() => setActiveSourceIndex(idx)}
                      >
                        {idx + 1}
                      </Button>
                    ))}
                  </Space>
                </div>
              )}
              {(activeSourceId || activeSourceReason || activeSourceScore !== undefined) && (
                <div className="source-provenance">
                  <Text type="secondary">定位溯源</Text>
                  <Space wrap size={6}>
                    {activeEvidenceSourceId && <Tag color="blue">定位片段</Tag>}
                    {activeSourceReason && <Tag color="geekblue">{sourceReasonLabel(activeSourceReason)}</Tag>}
                    {activeSourceScore !== undefined && <Tag>召回分 {activeSourceScore.toFixed(3)}</Tag>}
                  </Space>
                </div>
              )}
              {activeSourceHtml ? (
                <StructuredSourceView
                  html={activeSourceHtml}
                  startNodeId={activeStartNodeId}
                  endNodeId={activeEndNodeId}
                  locator={activeLocator}
                />
              ) : activeSourceText ? (
                <pre className="source-text-view">{highlightPlainText(activeSourceText, activeLocator)}</pre>
              ) : isProcessing ? (
                <div className="empty-panel">
                  <Spin />
                  <Text type="secondary">审查完成后显示完整章节原文。</Text>
                </div>
              ) : (
                <Empty description="当前结果未携带可展示原文；请使用新版本重新审查该文档。" />
              )}
            </div>
          </div>
        </section>

        <section className="review-bottom-grid">
          <Card
            size="small"
            className="log-card"
            title={(
              <Space>
                {isProcessing ? <LoadingOutlined spin style={{ color: '#1677ff' }} /> : <InfoCircleOutlined />}
                <span>审查日志</span>
                <Tag color={isProcessing ? 'processing' : logs.some((log) => log.level === 'error') ? 'error' : 'default'}>
                  {logs.length} 条
                </Tag>
              </Space>
            )}
            styles={{ body: { padding: 0 } }}
          >
            <div className="log-window">
              {logs.length === 0 ? (
                <div className="log-empty">
                  <InfoCircleOutlined />
                  等待审查日志...
                </div>
              ) : (
                logs.map((log, idx) => {
                  const style = LOG_LEVEL_STYLES[log.level] || LOG_LEVEL_STYLES.info;
                  return (
                    <div key={`${log.time}-${idx}`} className="log-row" style={{ borderLeftColor: style.color }}>
                      <span className="log-time">{log.time}</span>
                      <span className="log-icon" style={{ color: style.color }}>{LOG_LEVEL_ICONS[log.level]}</span>
                      <span className="log-message">
                        {log.message}
                        {log.progress !== undefined && <span className="log-progress">({log.progress}%)</span>}
                      </span>
                    </div>
                  );
                })
              )}
              <div ref={logEndRef} />
            </div>
          </Card>

          <Card size="small" className="runtime-card" title="审查运行信息">
            <Space direction="vertical" size={8} style={{ width: '100%' }}>
              {task.failReason && <Text type="danger">失败原因：{task.failReason}</Text>}
              {failedChunks.length > 0 && (
                <div>
                  <Text strong>失败切片</Text>
                  <div className="runtime-list">
                    {failedChunks.map((fc, idx) => (
                      <div key={idx} className="runtime-item">
                        <span>#{String(fc.chunk || idx + 1)} {String(fc.chapterTitle || '')}</span>
                        {Boolean(fc.error) && <Text type="danger">{String(fc.error)}</Text>}
                      </div>
                    ))}
                  </div>
                </div>
              )}
              {chunkResults.length > 0 && (
                <div>
                  <Text strong>规则调度</Text>
                  <div className="runtime-list">
                    {chunkResults.map((cr, idx) => {
                      const applied = (cr.appliedRules || []) as string[];
                      const title = String(cr.chapterTitle || `切片 ${idx + 1}`);
                      return (
                        <div key={idx} className="runtime-item">
                          <div className="runtime-item-heading">
                            <span>{title}</span>
                            <Text type="secondary">{applied.length} 条规则</Text>
                          </div>
                          {applied.length > 0 && (
                            <div className="runtime-rule-names">{applied.join('、')}</div>
                          )}
                        </div>
                      );
                    })}
                  </div>
                </div>
              )}
              {chunkResults.length === 0 && !task.failReason && <Text type="secondary">暂无运行明细。</Text>}
            </Space>
          </Card>
        </section>
      </div>
      <Modal
        title="人工复核"
        open={manualReviewOpen}
        onOk={handleManualReviewSubmit}
        onCancel={() => setManualReviewOpen(false)}
        confirmLoading={manualSaving}
        okText="保存"
        cancelText="取消"
        destroyOnClose
      >
        <Form form={manualForm} layout="vertical">
          <Form.Item
            name="finalStatus"
            label="最终判定"
            rules={[{ required: true, message: '请选择最终判定' }]}
          >
            <Select
              options={[
                { label: '通过', value: 'Pass' },
                { label: '不通过', value: 'Fail' },
                { label: '待复核', value: 'Review' },
              ]}
            />
          </Form.Item>
          <Form.Item name="accepted" label="是否接受系统意见">
            <Select
              allowClear
              placeholder="请选择"
              options={[
                { label: '接受', value: 'true' },
                { label: '不接受', value: 'false' },
              ]}
            />
          </Form.Item>
          <Form.Item name="comment" label="复核备注">
            <Input.TextArea rows={4} placeholder="填写人工复核依据、改判原因或处理意见" />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}

export default ReviewWorkspacePage;
