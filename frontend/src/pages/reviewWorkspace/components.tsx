import { useEffect, useRef } from 'react';
import { Virtuoso } from 'react-virtuoso';
import {
  Button,
  Card,
  Empty,
  Form,
  Input,
  Modal,
  Progress,
  Select,
  Space,
  Spin,
  Tag,
  Typography,
} from 'antd';
import {
  ArrowLeftOutlined,
  CheckCircleOutlined,
  CloseCircleOutlined,
  DownloadOutlined,
  InfoCircleOutlined,
  LoadingOutlined,
  ReloadOutlined,
  WarningOutlined,
} from '@ant-design/icons';
import { PIPELINE_COLOR, PIPELINE_LABEL } from '../../api/pipelineApi';
import { STATUS_LABELS } from '../../utils/constants';
import type { LogEntry } from '../../store/logStore';
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

const { Title, Text } = Typography;

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

function ReviewPageHeader({ workspace }: { workspace: ReviewWorkspaceViewModel }) {
  const { task } = workspace;
  if (!task) return null;

  return (
    <div className="page-header review-page-header">
      <Space>
        <Button icon={<ArrowLeftOutlined />} onClick={workspace.goDashboard}>
          返回
        </Button>
        <Title level={5} style={{ margin: 0 }}>{task.fileName}</Title>
        <Tag color={PIPELINE_COLOR[workspace.reviewMode]}>{PIPELINE_LABEL[workspace.reviewMode]}</Tag>
        <Tag color={workspace.status === 'completed' ? 'success' : workspace.status === 'failed' ? 'error' : workspace.status === 'cancelled' ? 'warning' : 'processing'}>
          {STATUS_LABELS[workspace.status] || task.status}
        </Tag>
      </Space>
      <Space wrap>
        <Text type="secondary">模型：{task.selectedModel}</Text>
        {workspace.status === 'completed' && task.aiResult && (
          <Button icon={<DownloadOutlined />} loading={workspace.exporting} onClick={workspace.handleExportExcel}>
            导出Excel
          </Button>
        )}
        {workspace.status === 'completed' && task.aiResult && (
          <Button icon={<DownloadOutlined />} loading={workspace.exportingReport} onClick={workspace.handleExportReport}>
            导出报告
          </Button>
        )}
        {workspace.status === 'completed' && task.aiResult && (
          <Button icon={<DownloadOutlined />} loading={workspace.exportingAudit} onClick={workspace.handleExportAudit}>
            导出审计
          </Button>
        )}
        {workspace.status === 'completed' && workspace.failedChunkCount > 0 && (
          <Button icon={<ReloadOutlined />} loading={workspace.retryingFailed} onClick={workspace.handleRetryFailedChunks}>
            重审失败切片
          </Button>
        )}
        {!workspace.isProcessing && (
          <Button type="primary" icon={<ReloadOutlined />} loading={workspace.reReviewing} onClick={workspace.handleReReview}>
            重新审查
          </Button>
        )}
      </Space>
    </div>
  );
}

function OverviewStrip({ workspace }: { workspace: ReviewWorkspaceViewModel }) {
  return (
    <section className="review-overview-strip" aria-label="审查概要">
      <div className="overview-item">
        <span className="overview-label">问题数</span>
        <strong className={workspace.problemCount > 0 ? 'danger-text' : undefined}>{workspace.problemCount}</strong>
      </div>
      <div className="overview-item">
        <span className="overview-label">文档切片</span>
        <strong>{workspace.displayedChunkCount}</strong>
      </div>
      <div className="overview-item">
        <span className="overview-label">失败切片</span>
        <strong className={workspace.failedChunkCount > 0 ? 'danger-text' : undefined}>{workspace.failedChunkCount}</strong>
      </div>
      <div className="overview-tags">
        {workspace.hasCheckMatrix && Object.entries(workspace.checkStatusCounts).map(([name, count]) => (
          count > 0 ? <Tag key={name} color={checkStatusColor(name)}>{CHECK_STATUS_LABELS[name] || name} {count}</Tag> : null
        ))}
        {Object.entries(workspace.categoryCounts).slice(0, 6).map(([name, count]) => (
          <Tag key={name}>{name} {count}</Tag>
        ))}
      </div>
    </section>
  );
}

function ProgressLine({ workspace }: { workspace: ReviewWorkspaceViewModel }) {
  if (!workspace.isProcessing || workspace.wsProgress <= 0) return null;
  return (
    <div className="review-progress-line">
      <LoadingOutlined spin />
      <Progress percent={workspace.wsProgress} status="active" showInfo={false} />
      <Text type="secondary">{workspace.wsProgress}%</Text>
    </div>
  );
}

function FindingCard({
  active,
  hasCheckMatrix,
  index,
  item,
  manualSaving,
  onInlineDecision,
  onSelect,
}: {
  active: boolean;
  hasCheckMatrix: boolean;
  index: number;
  item: Record<string, unknown>;
  manualSaving: boolean;
  onInlineDecision: ReviewWorkspaceViewModel['handleInlineManualDecision'];
  onSelect: (index: number) => void;
}) {
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
  const showManualDecision = needsManualCheck || hasCheckMatrix;
  const sourceChunkNo = numericField(item, ['sourceChunk', 'chunk']);
  const missingItems = Array.isArray(item.missing_items) ? item.missing_items : [];
  const statusClass = statusValue.replace(/[^A-Za-z0-9_-]/g, '-');
  // RAG：一个检查项展开成多条违规时的序号，以及两阶段复核结论。
  const verifyStatus = textField(item, ['verifyStatus']);
  const violationCount = numericField(item, ['violationCount']) ?? 0;

  return (
    <div
      role="button"
      tabIndex={0}
      className={`finding-card ${hasCheckMatrix ? `check-status-${statusClass || 'Review'}` : 'finding-card-neutral'} ${active ? 'active' : ''}`}
      onClick={() => onSelect(index)}
      onKeyDown={(event) => {
        if (event.key === 'Enter' || event.key === ' ') {
          event.preventDefault();
          onSelect(index);
        }
      }}
    >
      <div className="finding-card-top">
        <Space wrap size={4}>
          {hasCheckMatrix && (
            <Tag color={checkStatusColor(statusValue)}>{CHECK_STATUS_LABELS[statusValue] || statusValue || '待复核'}</Tag>
          )}
          {category && <Tag>{category}</Tag>}
          {hasCheckMatrix && rule && <Tag color="blue">{ruleCode ? `${ruleCode} ${rule}` : rule}</Tag>}
          {!hasCheckMatrix && ruleCode && <Tag color="blue">{ruleCode}</Tag>}
          {!hasCheckMatrix && checkCode && <Tag color="cyan">{checkCode}</Tag>}
          {manualStatus && <Tag color={checkStatusColor(manualStatus)}>人工：{CHECK_STATUS_LABELS[manualStatus] || manualStatus}</Tag>}
          {violationCount > 1 && (
            <Tag color="volcano">违规 {(Number(item.violationIndex) || 0) + 1}/{violationCount}</Tag>
          )}
          {verifyStatus === 'CONFIRMED' && <Tag color="red">复核确认</Tag>}
          {verifyStatus === 'UNCERTAIN' && <Tag color="gold">复核待定</Tag>}
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
                onChange={(value: 'Pass' | 'Fail' | 'Review') => onInlineDecision(item, value)}
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
          <p>{missingItems.map((missingItem) => String(missingItem)).join('；')}</p>
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
}

function ResultsPanel({ workspace }: { workspace: ReviewWorkspaceViewModel }) {
  const { task } = workspace;

  return (
    <div className="review-results-panel">
      <div className="panel-header">
        <div>
          <h3>{workspace.hasCheckMatrix ? '检查项判定矩阵' : '大模型审查结果'}</h3>
          <Text type="secondary">
            {workspace.hasCheckMatrix
              ? '点击检查项查看原文。判定仅为通过 / 不通过 / 待复核三种；证据不足、部分满足或不适用均判为待复核，交人工复核。'
              : '点击任一问题，右侧定位并显示完整章节原文。'}
          </Text>
        </div>
        <Tag>{workspace.reviewItems.length} 条</Tag>
      </div>

      {workspace.reviewItems.length === 0 ? (
        <div className="findings-list">
          <div className="empty-panel">
            {workspace.isProcessing ? (
              <>
                <Spin />
                <Text type="secondary">AI 正在审查中，日志见页面底部。</Text>
              </>
            ) : task?.aiResult ? (
              <Empty description={workspace.hasCheckMatrix ? '暂无检查项判定' : '未发现审查问题'} />
            ) : (
              <Empty description="暂无审查结果" />
            )}
          </div>
        </div>
      ) : (
        // 虚拟滚动：只渲染可视区的卡片，避免数百个检查项一次性挂载（含 AntD Select）拖慢首屏。
        <Virtuoso
          className="findings-list"
          data={workspace.reviewItems}
          computeItemKey={(index) => index}
          increaseViewportBy={300}
          itemContent={(index, item) => (
            <div style={{ paddingBottom: 10 }}>
              <FindingCard
                active={index === workspace.activeIndex}
                hasCheckMatrix={workspace.hasCheckMatrix}
                index={index}
                item={item}
                manualSaving={workspace.manualSaving}
                onInlineDecision={workspace.handleInlineManualDecision}
                onSelect={workspace.selectIssue}
              />
            </div>
          )}
        />
      )}
    </div>
  );
}

function SourcePanel({ workspace }: { workspace: ReviewWorkspaceViewModel }) {
  return (
    <div className="review-source-panel">
      <div className="panel-header">
        <div>
          <h3>对应原文</h3>
          <Text type="secondary">{workspace.activeSourceTitle || '完整章节原文'}</Text>
        </div>
        <Space wrap size={4}>
          {(workspace.activeSourceChapterLabel || workspace.activeSourceId) && (
            <Tag color="blue">{workspace.activeSourceChapterLabel || workspace.activeSourceId}</Tag>
          )}
          {workspace.activeSourceLength !== undefined && <Tag>{workspace.activeSourceLength} 字</Tag>}
          {workspace.activeSourceTokens !== undefined && <Tag>{workspace.activeSourceTokens} tokens</Tag>}
          {workspace.hasCheckMatrix && workspace.activeItem && (
            <Button size="small" type="primary" onClick={() => workspace.openManualReview(workspace.activeItem)}>
              人工复核
            </Button>
          )}
        </Space>
      </div>

      <div className="source-content">
        {workspace.sourceCandidates.length > 1 && (
          <div className="source-switcher">
            <Text type="secondary">关联章节</Text>
            <Space wrap size={6}>
              {workspace.sourceCandidates.map((source, index) => (
                <Button
                  key={sourceCandidateKey(source) || index}
                  size="small"
                  type={index === workspace.activeSourceSafeIndex ? 'primary' : 'default'}
                  onClick={() => workspace.setActiveSourceIndex(index)}
                >
                  {index + 1}
                </Button>
              ))}
            </Space>
          </div>
        )}
        {(workspace.activeSourceId || workspace.activeSourceReason || workspace.activeSourceScore !== undefined) && (
          <div className="source-provenance">
            <Text type="secondary">定位溯源</Text>
            <Space wrap size={6}>
              {workspace.activeEvidenceSourceId && <Tag color="blue">定位片段</Tag>}
              {workspace.activeSourceReason && <Tag color="geekblue">{sourceReasonLabel(workspace.activeSourceReason)}</Tag>}
              {workspace.activeSourceScore !== undefined && <Tag>召回分 {workspace.activeSourceScore.toFixed(3)}</Tag>}
            </Space>
          </div>
        )}
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
          <div className="empty-panel">
            <Spin />
            <Text type="secondary">审查完成后显示完整章节原文。</Text>
          </div>
        ) : workspace.sourcesLoading ? (
          <div className="empty-panel">
            <Spin />
            <Text type="secondary">正在加载原文…</Text>
          </div>
        ) : (
          <Empty description="当前结果未携带可展示原文；请使用新版本重新审查该文档。" />
        )}
      </div>
    </div>
  );
}

function MainGrid({ workspace }: { workspace: ReviewWorkspaceViewModel }) {
  return (
    <section className="review-main-grid">
      <ResultsPanel workspace={workspace} />
      <SourcePanel workspace={workspace} />
    </section>
  );
}

function LogCard({ workspace }: { workspace: ReviewWorkspaceViewModel }) {
  return (
    <Card
      size="small"
      className="log-card"
      title={(
        <Space>
          {workspace.isProcessing ? <LoadingOutlined spin style={{ color: '#1677ff' }} /> : <InfoCircleOutlined />}
          <span>审查日志</span>
          <Tag color={workspace.isProcessing ? 'processing' : workspace.logs.some((log) => log.level === 'error') ? 'error' : 'default'}>
            {workspace.logs.length} 条
          </Tag>
        </Space>
      )}
      styles={{ body: { padding: 0 } }}
    >
      <div className="log-window">
        {workspace.logs.length === 0 ? (
          <div className="log-empty">
            <InfoCircleOutlined />
            等待审查日志...
          </div>
        ) : (
          workspace.logs.map((log: LogEntry, index) => {
            const style = LOG_LEVEL_STYLES[log.level] || LOG_LEVEL_STYLES.info;
            return (
              <div key={`${log.time}-${index}`} className="log-row" style={{ borderLeftColor: style.color }}>
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
        <div ref={workspace.logEndRef} />
      </div>
    </Card>
  );
}

function RuntimeCard({ workspace }: { workspace: ReviewWorkspaceViewModel }) {
  const { task } = workspace;
  if (!task) return null;

  return (
    <Card size="small" className="runtime-card" title="审查运行信息">
      <Space direction="vertical" size={8} style={{ width: '100%' }}>
        {task.failReason && <Text type="danger">失败原因：{task.failReason}</Text>}
        {workspace.failedChunks.length > 0 && (
          <div>
            <Text strong>失败切片</Text>
            <div className="runtime-list">
              {workspace.failedChunks.map((failedChunk, index) => (
                <div key={index} className="runtime-item">
                  <span>#{String(failedChunk.chunk || index + 1)} {String(failedChunk.chapterTitle || '')}</span>
                  {Boolean(failedChunk.error) && <Text type="danger">{String(failedChunk.error)}</Text>}
                </div>
              ))}
            </div>
          </div>
        )}
        {workspace.chunkResults.length > 0 && (
          <div>
            <Text strong>规则调度</Text>
            <div className="runtime-list">
              {workspace.chunkResults.map((chunkResult, index) => {
                const applied = (chunkResult.appliedRules || []) as string[];
                const title = String(chunkResult.chapterTitle || `切片 ${index + 1}`);
                return (
                  <div key={index} className="runtime-item">
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
        {workspace.chunkResults.length === 0 && !task.failReason && <Text type="secondary">暂无运行明细。</Text>}
      </Space>
    </Card>
  );
}

function BottomGrid({ workspace }: { workspace: ReviewWorkspaceViewModel }) {
  return (
    <section className="review-bottom-grid">
      <LogCard workspace={workspace} />
      <RuntimeCard workspace={workspace} />
    </section>
  );
}

function ManualReviewModal({ workspace }: { workspace: ReviewWorkspaceViewModel }) {
  return (
    <Modal
      title="人工复核"
      open={workspace.manualReviewOpen}
      onOk={workspace.handleManualReviewSubmit}
      onCancel={workspace.closeManualReview}
      confirmLoading={workspace.manualSaving}
      okText="保存"
      cancelText="取消"
      destroyOnClose
    >
      <Form form={workspace.manualForm} layout="vertical">
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
  );
}

export function ReviewWorkspaceContent({ workspace }: { workspace: ReviewWorkspaceViewModel }) {
  return (
    <div className="review-page">
      <ReviewPageHeader workspace={workspace} />
      <div className="review-workspace">
        <OverviewStrip workspace={workspace} />
        <ProgressLine workspace={workspace} />
        <MainGrid workspace={workspace} />
        <BottomGrid workspace={workspace} />
      </div>
      <ManualReviewModal workspace={workspace} />
    </div>
  );
}
