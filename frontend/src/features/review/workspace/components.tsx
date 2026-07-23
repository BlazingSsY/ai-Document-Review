import { useEffect, useMemo, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import { Virtuoso } from 'react-virtuoso';
import {
  Badge,
  Button,
  Drawer,
  Dropdown,
  Empty,
  Form,
  Input,
  Modal,
  Progress,
  Radio,
  Segmented,
  Select,
  Space,
  Spin,
  Tag,
  Tooltip,
  Typography,
} from 'antd';
import type { MenuProps } from 'antd';
import {
  ArrowLeftOutlined,
  CheckCircleFilled,
  CheckCircleOutlined,
  ClockCircleOutlined,
  CloseCircleOutlined,
  DownloadOutlined,
  DownOutlined,
  FileSearchOutlined,
  FilterOutlined,
  InfoCircleOutlined,
  LeftOutlined,
  LoadingOutlined,
  ReloadOutlined,
  RightOutlined,
  SearchOutlined,
  WarningOutlined,
} from '@ant-design/icons';
import { PIPELINE_COLOR, PIPELINE_LABEL } from '../api/pipelineApi';
import { STATUS_LABELS } from '../../../shared/utils/constants';
import type { LogEntry } from '../store/logStore';
import {
  buildHighlightedSourceHtml,
  CHECK_STATUS_LABELS,
  checkStatusColor,
  confidenceLabel,
  locatorCandidates,
  numericField,
  sourceCandidateKey,
  sourceReasonLabel,
  textField,
} from './helpers';
import type { ReviewWorkspaceViewModel } from './useReviewWorkspace';

const { Text } = Typography;

const LOG_LEVEL_STYLES: Record<string, { color: string }> = {
  info: { color: '#1677ff' },
  error: { color: '#ff4d4f' },
  success: { color: '#52c41a' },
  warning: { color: '#faad14' },
};

const LOG_LEVEL_ICONS: Record<string, ReactNode> = {
  info: <InfoCircleOutlined />,
  error: <CloseCircleOutlined />,
  success: <CheckCircleOutlined />,
  warning: <WarningOutlined />,
};

type ThreeState = 'Pass' | 'Fail' | 'Review';
type ResultFilter = 'all' | ThreeState;

interface ReviewCounts {
  total: number;
  Pass: number;
  Fail: number;
  Review: number;
}

function highlightPlainText(text: string, locator: string): ReactNode {
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

function evidenceForDisplay(value: string): string {
  const trimmed = value.trim();
  if ((trimmed.startsWith('“') && trimmed.endsWith('”'))
    || (trimmed.startsWith('"') && trimmed.endsWith('"'))) {
    return trimmed.slice(1, -1).trim();
  }
  return trimmed;
}

function normalizeThreeState(status: string): ThreeState {
  if (status === 'Pass') return 'Pass';
  if (status === 'Fail') return 'Fail';
  return 'Review';
}

function itemThreeState(item: Record<string, unknown>, hasCheckMatrix: boolean): ThreeState {
  if (!hasCheckMatrix) return 'Fail';
  return normalizeThreeState(textField(item, ['manualStatus', 'status']));
}

function summarizeReviewItems(workspace: ReviewWorkspaceViewModel): ReviewCounts {
  const counts: ReviewCounts = { total: workspace.reviewItems.length, Pass: 0, Fail: 0, Review: 0 };
  workspace.reviewItems.forEach((item) => {
    counts[itemThreeState(item, workspace.hasCheckMatrix)] += 1;
  });
  return counts;
}

function formatTaskTime(value: string | undefined): string {
  if (!value) return '';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return date.toLocaleString('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  });
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

function ReviewPageHeader({
  workspace,
  onOpenRuntime,
}: {
  workspace: ReviewWorkspaceViewModel;
  onOpenRuntime: () => void;
}) {
  const { task } = workspace;
  if (!task) return null;

  const statusColor = workspace.status === 'completed'
    ? 'success'
    : workspace.status === 'failed'
      ? 'error'
      : workspace.status === 'cancelled'
        ? 'warning'
        : 'processing';
  const exportLoading = workspace.exporting || workspace.exportingReport || workspace.exportingAudit;
  const exportItems: MenuProps['items'] = [
    { key: 'report', label: 'Word 审查报告' },
    { key: 'excel', label: 'Excel 审查意见表' },
    { key: 'audit', label: 'JSON 审计日志' },
  ];

  const handleExportMenu: MenuProps['onClick'] = ({ key }) => {
    if (key === 'report') void workspace.handleExportReport();
    if (key === 'excel') void workspace.handleExportExcel();
    if (key === 'audit') void workspace.handleExportAudit();
  };

  return (
    <header className="review-page-header">
      <div className="review-heading-main">
        <Tooltip title="返回工作台">
          <Button
            aria-label="返回工作台"
            className="review-back-button"
            icon={<ArrowLeftOutlined />}
            onClick={workspace.goDashboard}
          />
        </Tooltip>
        <div className="review-heading-copy">
          <div className="review-title-line">
            <h1>{task.fileName}</h1>
            <Tag color={PIPELINE_COLOR[workspace.reviewMode]}>{PIPELINE_LABEL[workspace.reviewMode]}</Tag>
            <Tag color={statusColor}>{STATUS_LABELS[workspace.status] || task.status}</Tag>
          </div>
          <p>
            <FileSearchOutlined />
            <span>模型：{task.selectedModel || '未记录'}</span>
            {task.createdAt && <><i /> <span>{formatTaskTime(task.createdAt)}</span></>}
          </p>
        </div>
      </div>

      <Space className="review-heading-actions" size={6} wrap>
        <Badge count={workspace.failedChunkCount} size="small" offset={[-2, 2]}>
          <Button
            icon={workspace.isProcessing ? <LoadingOutlined spin /> : <InfoCircleOutlined />}
            onClick={onOpenRuntime}
          >
            运行信息
          </Button>
        </Badge>
        {workspace.status === 'completed' && task.aiResult && (
          <Dropdown menu={{ items: exportItems, onClick: handleExportMenu }} placement="bottomRight">
            <Button icon={<DownloadOutlined />} loading={exportLoading}>导出成果</Button>
          </Dropdown>
        )}
        {workspace.status === 'completed' && workspace.failedChunkCount > 0 && (
          <Button
            icon={<ReloadOutlined />}
            loading={workspace.retryingFailed}
            onClick={workspace.handleRetryFailedChunks}
          >
            重审失败切片
          </Button>
        )}
        {!workspace.isProcessing && (
          <Button
            type="primary"
            icon={<ReloadOutlined />}
            loading={workspace.reReviewing}
            onClick={workspace.handleReReview}
          >
            重新审查
          </Button>
        )}
      </Space>
    </header>
  );
}

function WorkflowStep({ label, state, last }: { label: string; state: 'done' | 'current' | 'waiting'; last: boolean }) {
  return (
    <div className={`review-workflow-step ${state}`}>
      {state === 'done' ? <CheckCircleFilled /> : state === 'current' ? <LoadingOutlined spin /> : <ClockCircleOutlined />}
      <span>{label}</span>
      {!last && <i />}
    </div>
  );
}

function ReviewMetric({ label, value, tone }: { label: string; value: string | number; tone?: string }) {
  return (
    <div className={`review-metric ${tone || ''}`}>
      <span>{label}</span>
      <strong>{value}</strong>
    </div>
  );
}

function ExecutionStrip({ workspace }: { workspace: ReviewWorkspaceViewModel }) {
  const counts = summarizeReviewItems(workspace);
  const progress = workspace.status === 'completed'
    ? 100
    : Math.max(0, Math.min(100, workspace.wsProgress || Number(workspace.task?.progress || 0)));
  // Keep these boundaries aligned with ReviewService's progress events. The stages
  // are intentionally not equal 20% buckets: model review starts at 35% and then
  // occupies most of the runtime. Equal buckets incorrectly showed "章节切片"
  // while the backend was already waiting for the first model responses.
  const stages = [
    { label: '文档解析', startsAt: 0 },
    { label: '规则加载', startsAt: 20 },
    { label: '章节切片', startsAt: 25 },
    { label: '模型审查', startsAt: 35 },
    { label: '结果汇总', startsAt: 92 },
  ];
  let currentStage = 0;
  stages.forEach((stage, index) => {
    if (progress >= stage.startsAt) currentStage = index;
  });
  const completedStages = workspace.status === 'completed' ? stages.length : currentStage;
  const activeStage = workspace.isProcessing ? currentStage : -1;

  return (
    <section className="review-status-strip" aria-label="审查执行状态">
      <div className="review-workflow">
        {stages.map((stage, index) => (
          <WorkflowStep
            key={stage.label}
            label={stage.label}
            last={index === stages.length - 1}
            state={index < completedStages ? 'done' : index === activeStage ? 'current' : 'waiting'}
          />
        ))}
      </div>
      <div className="status-strip-divider" />
      <ReviewMetric label="检查项" value={counts.total} />
      <ReviewMetric label="通过" value={counts.Pass} tone="success" />
      <ReviewMetric label="不通过" value={counts.Fail} tone="error" />
      <ReviewMetric label="待复核" value={counts.Review} tone="warning" />
      <div className="review-coverage">
        <div><span>{workspace.isProcessing ? '审查进度' : '审查覆盖率'}</span><strong>{progress}%</strong></div>
        <Progress percent={progress} showInfo={false} status={workspace.status === 'failed' ? 'exception' : 'normal'} size="small" />
      </div>
    </section>
  );
}

function FindingItem({
  active,
  detailsFooter,
  expanded,
  hasCheckMatrix,
  item,
  itemRef,
  onSelect,
}: {
  active: boolean;
  detailsFooter?: ReactNode;
  expanded: boolean;
  hasCheckMatrix: boolean;
  item: Record<string, unknown>;
  itemRef?: (element: HTMLElement | null) => void;
  onSelect: () => void;
}) {
  const status = itemThreeState(item, hasCheckMatrix);
  const rawStatus = textField(item, ['manualStatus', 'status']);
  const manualStatus = textField(item, ['manualStatus']);
  const category = textField(item, ['category']);
  const title = hasCheckMatrix
    ? textField(item, ['check_question', 'question', 'description'])
    : textField(item, ['description', 'explanation', 'issue', 'problem', 'summary']);
  const reason = textField(item, ['reason', 'explanation']);
  const evidence = textField(item, ['evidence', 'originalText']);
  const suggestion = textField(item, ['suggestion', 'recommendation']);
  const excerpt = reason || evidence || suggestion;
  const rule = textField(item, ['ruleName', 'rule_name', 'rule']);
  const ruleCode = textField(item, ['rule_code', 'ruleCode']);
  const checkCode = textField(item, ['check_code', 'checkCode']);
  const confidence = textField(item, ['confidence']);
  const chapter = textField(item, ['sectionPath', 'chapterTitle', 'sourceTitle']);
  const sourceChunk = numericField(item, ['sourceChunk', 'chunk']);
  const location = chapter || (sourceChunk ? `切片 ${sourceChunk}` : '点击查看定位证据');
  const itemCode = checkCode || ruleCode;
  const sourceRefs = item.sourceRefs;
  const evidenceCount = Array.isArray(sourceRefs) ? sourceRefs.length : evidence ? 1 : 0;

  return (
    <article ref={itemRef} className={`review-finding-item status-${status} ${active ? 'active' : ''}`}>
      <button
        type="button"
        className="finding-item-trigger"
        aria-expanded={expanded}
        onClick={onSelect}
      >
        <div className="finding-item-heading">
          <Space size={5} wrap>
            <Tag color={checkStatusColor(status)}>{CHECK_STATUS_LABELS[status]}</Tag>
            {category && <Tag>{category}</Tag>}
            {itemCode && <span className="finding-code">{itemCode}</span>}
            {manualStatus && <Tag color="geekblue">人工：{CHECK_STATUS_LABELS[normalizeThreeState(manualStatus)]}</Tag>}
          </Space>
          {expanded ? <DownOutlined className="finding-arrow active" /> : <RightOutlined className="finding-arrow" />}
        </div>
        <h3>{title || rule || '未命名检查项'}</h3>
        {!expanded && excerpt && <p className="finding-summary-preview">{excerpt}</p>}
        <div className="finding-item-footer">
          <span><FileSearchOutlined />{location}</span>
          {confidence && <span className="finding-confidence">{confidenceLabel(confidence)}</span>}
          {!confidence && rawStatus === 'Partial' && <span className="finding-confidence">部分满足</span>}
        </div>
      </button>

      {expanded && (
        <div className="finding-detail-panel" aria-label="当前检查项审查详情">
          <div className="finding-detail-heading">
            <strong>审查详情</strong>
            <span>{[ruleCode || rule, `${evidenceCount} 处证据`].filter(Boolean).join(' · ')}</span>
          </div>
          <div className="finding-detail-section">
            <span>判定结论</span>
            <p>{reason || '系统未返回补充判定理由，请结合右侧定位原文进行人工复核。'}</p>
          </div>
          {evidence && status !== 'Pass' && (
            <div className="finding-detail-section evidence">
              <span>问题原文</span>
              <blockquote>{evidenceForDisplay(evidence)}</blockquote>
            </div>
          )}
          <div className="finding-detail-section suggestion">
            <span>修改建议</span>
            <p>{suggestion || (status === 'Pass' ? '当前检查项无需修改。' : '系统未提供修改建议，请结合判定依据处理。')}</p>
          </div>
          {detailsFooter}
        </div>
      )}
    </article>
  );
}

function matchesFilter(item: Record<string, unknown>, filter: ResultFilter, hasCheckMatrix: boolean): boolean {
  return filter === 'all' || itemThreeState(item, hasCheckMatrix) === filter;
}

function ResultsPanel({ workspace }: { workspace: ReviewWorkspaceViewModel }) {
  const counts = summarizeReviewItems(workspace);
  const [filter, setFilter] = useState<ResultFilter>(() => workspace.hasCheckMatrix && counts.Fail > 0 ? 'Fail' : 'all');
  const [search, setSearch] = useState('');
  const [expandedFindingIndex, setExpandedFindingIndex] = useState<number | null>(null);
  const findingsScrollerRef = useRef<HTMLElement | Window | null>(null);
  const findingItemRefs = useRef(new Map<number, HTMLElement>());

  useEffect(() => {
    setExpandedFindingIndex((current) => current === workspace.activeIndex ? current : null);
  }, [workspace.activeIndex]);
  const normalizedSearch = search.trim().toLowerCase();

  const filteredItems = useMemo(() => workspace.reviewItems
    .map((item, originalIndex) => ({ item, originalIndex }))
    .filter(({ item }) => matchesFilter(item, filter, workspace.hasCheckMatrix))
    .filter(({ item }) => {
      if (!normalizedSearch) return true;
      return [
        textField(item, ['check_question', 'question', 'description', 'explanation', 'issue', 'problem', 'summary']),
        textField(item, ['ruleName', 'rule_name', 'rule']),
        textField(item, ['rule_code', 'ruleCode']),
        textField(item, ['check_code', 'checkCode']),
        textField(item, ['evidence', 'reason']),
      ].join(' ').toLowerCase().includes(normalizedSearch);
    }), [filter, normalizedSearch, workspace.hasCheckMatrix, workspace.reviewItems]);

  useEffect(() => {
    if (expandedFindingIndex === null) return undefined;

    const frameId = window.requestAnimationFrame(() => {
      const scroller = findingsScrollerRef.current;
      const findingItem = findingItemRefs.current.get(expandedFindingIndex);
      if (!(scroller instanceof HTMLElement) || !findingItem) return;

      const offsetToTop = findingItem.getBoundingClientRect().top - scroller.getBoundingClientRect().top;
      scroller.scrollTo({
        top: scroller.scrollTop + offsetToTop,
        behavior: window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth',
      });
    });

    return () => window.cancelAnimationFrame(frameId);
  }, [expandedFindingIndex]);

  const handleFilterChange = (value: string | number) => {
    const nextFilter = value as ResultFilter;
    setFilter(nextFilter);
    setExpandedFindingIndex(null);
    const firstMatch = workspace.reviewItems.findIndex((item) => matchesFilter(item, nextFilter, workspace.hasCheckMatrix));
    if (firstMatch >= 0) workspace.selectIssue(firstMatch);
  };

  const handleFindingSelect = (index: number) => {
    workspace.selectIssue(index);
    setExpandedFindingIndex((current) => current === index ? null : index);
  };

  return (
    <aside className="review-findings-pane">
      <div className="review-pane-header">
        <div>
          <h2>{workspace.hasCheckMatrix ? '检查项判定' : '审查问题'}</h2>
          <span>{workspace.hasCheckMatrix ? '按规则逐项复核审查结论' : '按问题查看判定依据与完整原文'}</span>
        </div>
        <Tag>{counts.total} 条</Tag>
      </div>

      <div className="review-filter-bar">
        <Segmented
          block
          size="small"
          value={filter}
          onChange={handleFilterChange}
          options={[
            { label: `全部 ${counts.total}`, value: 'all' },
            { label: `不通过 ${counts.Fail}`, value: 'Fail' },
            { label: `待复核 ${counts.Review}`, value: 'Review' },
            { label: `已通过 ${counts.Pass}`, value: 'Pass' },
          ]}
        />
        <Space.Compact block>
          <Input
            allowClear
            aria-label="搜索检查项"
            prefix={<SearchOutlined />}
            placeholder="搜索规则、检查项或证据"
            value={search}
            onChange={(event) => setSearch(event.target.value)}
          />
          <Tooltip title="筛选结果跟随上方结论分类">
            <Button aria-label="筛选说明" icon={<FilterOutlined />} />
          </Tooltip>
        </Space.Compact>
      </div>

      {filteredItems.length === 0 ? (
        <div className="review-empty-list">
          {workspace.isProcessing ? <><Spin /><Text type="secondary">AI 正在生成审查结果</Text></> : <Empty description="当前筛选条件下没有检查项" />}
        </div>
      ) : (
        <Virtuoso
          className="review-findings-list"
          data={filteredItems}
          increaseViewportBy={240}
          scrollerRef={(element) => {
            findingsScrollerRef.current = element;
          }}
          computeItemKey={(_, entry) => textField(entry.item, ['finding_id', 'findingId']) || entry.originalIndex}
          itemContent={(_, entry) => (
            <div className="review-finding-wrapper">
              <FindingItem
                active={entry.originalIndex === workspace.activeIndex}
                detailsFooter={entry.originalIndex === workspace.activeIndex && expandedFindingIndex === entry.originalIndex
                  ? <ManualReviewBar workspace={workspace} />
                  : undefined}
                expanded={entry.originalIndex === workspace.activeIndex && expandedFindingIndex === entry.originalIndex}
                hasCheckMatrix={workspace.hasCheckMatrix}
                item={entry.item}
                itemRef={(element) => {
                  if (element) findingItemRefs.current.set(entry.originalIndex, element);
                  else findingItemRefs.current.delete(entry.originalIndex);
                }}
                onSelect={() => handleFindingSelect(entry.originalIndex)}
              />
            </div>
          )}
        />
      )}

      <div className="review-list-footer">
        <span>显示 {filteredItems.length} / {counts.total} 条</span>
        <Space size={4}>
          <Button
            size="small"
            aria-label="上一检查项"
            icon={<LeftOutlined />}
            disabled={workspace.activeIndex <= 0}
            onClick={() => workspace.selectIssue(workspace.activeIndex - 1)}
          />
          <Button
            size="small"
            aria-label="下一检查项"
            icon={<RightOutlined />}
            disabled={workspace.activeIndex < 0 || workspace.activeIndex >= workspace.reviewItems.length - 1}
            onClick={() => workspace.selectIssue(workspace.activeIndex + 1)}
          />
        </Space>
      </div>
    </aside>
  );
}

function SourceEvidence({ workspace }: { workspace: ReviewWorkspaceViewModel }) {
  return (
    <div className="review-source-viewport">
      {workspace.activeSourceHtml ? (
        <StructuredSourceView
          html={workspace.activeSourceHtml}
          startNodeId={workspace.activeStartNodeId}
          endNodeId={workspace.activeEndNodeId}
          locator={workspace.activeLocator}
        />
      ) : workspace.activeSourceText ? (
        <pre className="source-text-view">{highlightPlainText(workspace.activeSourceText, workspace.activeLocator)}</pre>
      ) : workspace.isProcessing ? (
        <div className="review-source-empty"><Spin /><Text type="secondary">审查完成后显示完整章节原文</Text></div>
      ) : workspace.sourcesLoading ? (
        <div className="review-source-empty"><Spin /><Text type="secondary">正在加载原文</Text></div>
      ) : (
        <div className="review-source-empty"><Empty description="当前结果没有可展示的定位原文" /></div>
      )}
    </div>
  );
}

function ManualReviewBar({ workspace }: { workspace: ReviewWorkspaceViewModel }) {
  const item = workspace.activeItem;
  const itemIdentity = textField(item, ['finding_id', 'findingId', 'check_code', 'checkCode']) || String(workspace.activeIndex);
  const [decision, setDecision] = useState<ThreeState>('Review');
  const checkCode = textField(item, ['check_code', 'checkCode']);

  useEffect(() => {
    setDecision(normalizeThreeState(textField(item, ['manualStatus', 'status'])));
  }, [item, itemIdentity]);

  if (!item || !checkCode) return null;

  const handleSaveAndAdvance = async () => {
    const saved = await workspace.handleInlineManualDecision(item, decision);
    if (saved && workspace.activeIndex < workspace.reviewItems.length - 1) {
      workspace.selectIssue(workspace.activeIndex + 1);
    }
  };

  return (
    <footer className="manual-review-bar">
      <div className="manual-decision-control">
        <span className="review-section-label">人工复核</span>
        <Radio.Group
          size="small"
          buttonStyle="solid"
          value={decision}
          onChange={(event) => setDecision(event.target.value as ThreeState)}
        >
          <Radio.Button value="Pass">通过</Radio.Button>
          <Radio.Button value="Fail">不通过</Radio.Button>
          <Radio.Button value="Review">待复核</Radio.Button>
        </Radio.Group>
      </div>
      <Space size={8}>
        <Button onClick={() => workspace.openManualReview(item)}>复核备注</Button>
        <Button type="primary" loading={workspace.manualSaving} onClick={handleSaveAndAdvance}>
          保存并查看下一项
        </Button>
      </Space>
    </footer>
  );
}

function EvidencePanel({ workspace }: { workspace: ReviewWorkspaceViewModel }) {
  const item = workspace.activeItem;

  return (
    <main className="review-evidence-pane">
      <div className="review-pane-header evidence-heading source-only-heading">
        <div>
          <h2>原文定位</h2>
          <span>{workspace.activeSourceTitle || (item ? '完整章节原文' : '选择左侧检查项查看定位原文')}</span>
        </div>
        {item && (
          <Space className="source-heading-actions" size={5} wrap>
            {workspace.activeSourceChapterLabel && <Tag color="blue">{workspace.activeSourceChapterLabel}</Tag>}
            {workspace.activeSourceReason && <Tag>{sourceReasonLabel(workspace.activeSourceReason)}</Tag>}
            {workspace.sourceCandidates.length > 0 && (
              <Tag color="processing">证据 {workspace.activeSourceSafeIndex + 1} / {workspace.sourceCandidates.length}</Tag>
            )}
            <Tooltip title="上一处证据">
              <Button
                size="small"
                aria-label="上一处证据"
                icon={<LeftOutlined />}
                disabled={workspace.activeSourceSafeIndex <= 0}
                onClick={() => workspace.setActiveSourceIndex(workspace.activeSourceSafeIndex - 1)}
              />
            </Tooltip>
            <Tooltip title="下一处证据">
              <Button
                size="small"
                aria-label="下一处证据"
                icon={<RightOutlined />}
                disabled={workspace.activeSourceSafeIndex < 0 || workspace.activeSourceSafeIndex >= workspace.sourceCandidates.length - 1}
                onClick={() => workspace.setActiveSourceIndex(workspace.activeSourceSafeIndex + 1)}
              />
            </Tooltip>
          </Space>
        )}
      </div>

      {item ? (
        <SourceEvidence workspace={workspace} />
      ) : (
        <div className="review-source-empty">
          {workspace.isProcessing ? <><Spin /><Text type="secondary">AI 正在生成审查结果</Text></> : <Empty description="暂无审查结果" />}
        </div>
      )}
    </main>
  );
}

function ReviewMainSurface({ workspace }: { workspace: ReviewWorkspaceViewModel }) {
  return (
    <section className="review-main-surface">
      <ResultsPanel workspace={workspace} />
      <EvidencePanel workspace={workspace} />
    </section>
  );
}

function ReviewLogSection({ workspace }: { workspace: ReviewWorkspaceViewModel }) {
  return (
    <section className="runtime-section">
      <div className="runtime-section-heading">
        <Space>
          {workspace.isProcessing ? <LoadingOutlined spin className="runtime-running-icon" /> : <InfoCircleOutlined />}
          <strong>审查日志</strong>
        </Space>
        <Tag>{workspace.logs.length} 条</Tag>
      </div>
      <div className="runtime-log-window">
        {workspace.logs.length === 0 ? (
          <div className="runtime-empty">等待审查日志...</div>
        ) : workspace.logs.map((log: LogEntry, index) => {
          const style = LOG_LEVEL_STYLES[log.level] || LOG_LEVEL_STYLES.info;
          return (
            <div key={`${log.time}-${index}`} className="runtime-log-row" style={{ borderLeftColor: style.color }}>
              <span className="runtime-log-time">{log.time}</span>
              <span style={{ color: style.color }}>{LOG_LEVEL_ICONS[log.level]}</span>
              <span className="runtime-log-message">
                {log.message}
                {log.progress !== undefined && <em>({log.progress}%)</em>}
              </span>
            </div>
          );
        })}
        <div ref={workspace.logEndRef} />
      </div>
    </section>
  );
}

function RuleRuntimeSection({ workspace }: { workspace: ReviewWorkspaceViewModel }) {
  const { task } = workspace;
  if (!task) return null;

  return (
    <section className="runtime-section">
      <div className="runtime-section-heading"><strong>规则调度与失败信息</strong></div>
      <div className="runtime-details">
        {task.failReason && <div className="runtime-error">失败原因：{task.failReason}</div>}
        {workspace.failedChunks.length > 0 && (
          <div className="runtime-group">
            <Text strong>失败切片</Text>
            {workspace.failedChunks.map((chunk, index) => (
              <div key={index} className="runtime-detail-item">
                <span>#{String(chunk.chunk || index + 1)} {String(chunk.chapterTitle || '')}</span>
                {Boolean(chunk.error) && <Text type="danger">{String(chunk.error)}</Text>}
              </div>
            ))}
          </div>
        )}
        {workspace.chunkResults.length > 0 && (
          <div className="runtime-group">
            <Text strong>章节规则调度</Text>
            {workspace.chunkResults.map((chunk, index) => {
              const applied = (chunk.appliedRules || []) as string[];
              return (
                <div key={index} className="runtime-detail-item">
                  <div><span>{String(chunk.chapterTitle || `切片 ${index + 1}`)}</span><em>{applied.length} 条规则</em></div>
                  {applied.length > 0 && <p>{applied.join('、')}</p>}
                </div>
              );
            })}
          </div>
        )}
        {!task.failReason && workspace.failedChunks.length === 0 && workspace.chunkResults.length === 0 && (
          <div className="runtime-empty">暂无运行明细</div>
        )}
      </div>
    </section>
  );
}

function RuntimeDrawer({
  workspace,
  open,
  onClose,
}: {
  workspace: ReviewWorkspaceViewModel;
  open: boolean;
  onClose: () => void;
}) {
  return (
    <Drawer title="审查运行信息" width={600} open={open} onClose={onClose} destroyOnHidden>
      <div className="runtime-drawer-content">
        <ReviewLogSection workspace={workspace} />
        <RuleRuntimeSection workspace={workspace} />
      </div>
    </Drawer>
  );
}

function ManualReviewModal({ workspace }: { workspace: ReviewWorkspaceViewModel }) {
  return (
    <Modal
      title="人工复核备注"
      open={workspace.manualReviewOpen}
      onOk={workspace.handleManualReviewSubmit}
      onCancel={workspace.closeManualReview}
      confirmLoading={workspace.manualSaving}
      okText="保存"
      cancelText="取消"
      destroyOnHidden
    >
      <Form form={workspace.manualForm} layout="vertical">
        <Form.Item name="finalStatus" label="最终判定" rules={[{ required: true, message: '请选择最终判定' }]}>
          <Select options={[
            { label: '通过', value: 'Pass' },
            { label: '不通过', value: 'Fail' },
            { label: '待复核', value: 'Review' },
          ]} />
        </Form.Item>
        <Form.Item name="accepted" label="是否接受系统意见">
          <Select allowClear placeholder="请选择" options={[
            { label: '接受', value: 'true' },
            { label: '不接受', value: 'false' },
          ]} />
        </Form.Item>
        <Form.Item name="comment" label="复核备注">
          <Input.TextArea rows={4} placeholder="填写人工复核依据、改判原因或处理意见" />
        </Form.Item>
      </Form>
    </Modal>
  );
}

export function ReviewWorkspaceContent({ workspace }: { workspace: ReviewWorkspaceViewModel }) {
  const [runtimeOpen, setRuntimeOpen] = useState(false);

  return (
    <div className="review-page">
      <ReviewPageHeader workspace={workspace} onOpenRuntime={() => setRuntimeOpen(true)} />
      <ExecutionStrip workspace={workspace} />
      <ReviewMainSurface workspace={workspace} />
      <RuntimeDrawer workspace={workspace} open={runtimeOpen} onClose={() => setRuntimeOpen(false)} />
      <ManualReviewModal workspace={workspace} />
    </div>
  );
}
