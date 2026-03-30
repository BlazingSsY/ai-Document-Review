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
  Progress,
  message,
} from 'antd';
import {
  ArrowLeftOutlined,
  CheckCircleOutlined,
  WarningOutlined,
  ExclamationCircleOutlined,
  CloseCircleOutlined,
  LoadingOutlined,
  InfoCircleOutlined,
  ReloadOutlined,
  DownloadOutlined,
} from '@ant-design/icons';
import { getReviewDetail, reReview, exportReviewExcel, ReviewTask } from '../api/reviews';
import { STATUS_LABELS } from '../utils/constants';
import taskWebSocket, { TaskProgressMessage } from '../utils/websocket';
import '../styles/reviewWorkspace.css';

const { Title, Text, Paragraph } = Typography;

interface LogEntry {
  time: string;
  level: 'info' | 'error' | 'success' | 'warning';
  message: string;
  progress?: number;
}

/** Extract issues from the backend aiResult structure */
function extractIssues(aiResult: Record<string, unknown> | null): Array<Record<string, unknown>> {
  if (!aiResult) return [];
  const allIssues = aiResult.allIssues;
  if (Array.isArray(allIssues)) return allIssues;
  return [];
}

function normalizeStatus(status: string): string {
  return status?.toLowerCase() || 'pending';
}

function formatTime(date: Date): string {
  return date.toLocaleTimeString('zh-CN', { hour12: false, hour: '2-digit', minute: '2-digit', second: '2-digit' });
}

function getLogLevelForStatus(status: string): LogEntry['level'] {
  const s = status?.toUpperCase();
  if (s === 'COMPLETED') return 'success';
  if (s === 'FAILED') return 'error';
  if (s === 'CANCELLED') return 'warning';
  return 'info';
}

const LOG_LEVEL_STYLES: Record<string, { color: string; bg: string }> = {
  info: { color: '#1677ff', bg: '#e6f4ff' },
  error: { color: '#ff4d4f', bg: '#fff2f0' },
  success: { color: '#52c41a', bg: '#f6ffed' },
  warning: { color: '#faad14', bg: '#fffbe6' },
};

const LOG_LEVEL_ICONS: Record<string, React.ReactNode> = {
  info: <InfoCircleOutlined />,
  error: <CloseCircleOutlined />,
  success: <CheckCircleOutlined />,
  warning: <WarningOutlined />,
};

function ReviewWorkspacePage() {
  const { taskId } = useParams<{ taskId: string }>();
  const navigate = useNavigate();
  const [loading, setLoading] = useState(true);
  const [task, setTask] = useState<ReviewTask | null>(null);
  const [logs, setLogs] = useState<LogEntry[]>([]);
  const [wsProgress, setWsProgress] = useState<number>(0);
  const [reReviewing, setReReviewing] = useState(false);
  const [exporting, setExporting] = useState(false);
  const logEndRef = useRef<HTMLDivElement>(null);

  const addLog = useCallback((level: LogEntry['level'], message: string, progress?: number) => {
    setLogs((prev) => [...prev, { time: formatTime(new Date()), level, message, progress }]);
  }, []);

  const fetchDetail = useCallback(async () => {
    if (!taskId) return;
    setLoading(true);
    try {
      const res = await getReviewDetail(taskId);
      const t = res.data.data;
      setTask(t);
      // Add initial log based on current status
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
      const res = await reReview(taskId);
      const newId = res.data.data.id;
      message.success('重新审查任务已提交');
      // Reset logs and state before navigating to new task
      setLogs([]);
      setWsProgress(0);
      setTask(null);
      navigate(`/review/${newId}`, { replace: true });
    } catch {
      message.error('重新审查失败');
    } finally {
      setReReviewing(false);
    }
  };

  const handleExportExcel = async () => {
    if (!taskId) return;
    setExporting(true);
    try {
      const res = await exportReviewExcel(taskId);
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

  // Auto-scroll log panel
  useEffect(() => {
    logEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [logs]);

  // Subscribe to WebSocket for real-time updates
  // Also reset logs when taskId changes (e.g. after re-review navigation)
  useEffect(() => {
    if (!taskId) return;

    setLogs([]);
    setWsProgress(0);
    taskWebSocket.connect();

    const handler = (data: TaskProgressMessage) => {
      const level = getLogLevelForStatus(data.status);
      const msg = data.message || `状态更新: ${data.status}`;
      addLog(level, msg, data.progress);
      if (data.progress !== undefined) {
        setWsProgress(data.progress);
      }

      // Refresh task data on terminal states
      const s = data.status?.toUpperCase();
      if (s === 'COMPLETED' || s === 'FAILED' || s === 'CANCELLED') {
        // Re-fetch to get final result
        setTimeout(async () => {
          try {
            const res = await getReviewDetail(taskId);
            setTask(res.data.data);
          } catch { /* ignore */ }
        }, 500);
      }
    };

    taskWebSocket.subscribe(taskId, handler);
    return () => {
      taskWebSocket.unsubscribe(taskId, handler);
    };
  }, [taskId, addLog]);

  useEffect(() => {
    fetchDetail();
  }, [fetchDetail]);

  if (loading) {
    return (
      <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', height: '60vh' }}>
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
  const overallScore = task.aiResult?.overallScore as number | undefined;
  const totalChunks = task.aiResult?.totalChunks as number | undefined;
  const isProcessing = status === 'processing';

  return (
    <div>
      <div className="page-header" style={{ marginBottom: 12 }}>
        <Space>
          <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/dashboard')}>
            返回
          </Button>
          <Title level={5} style={{ margin: 0 }}>{task.fileName}</Title>
          <Tag color={status === 'completed' ? 'success' : status === 'failed' ? 'error' : status === 'cancelled' ? 'warning' : 'processing'}>
            {STATUS_LABELS[status] || task.status}
          </Tag>
        </Space>
        <Space>
          <Text type="secondary">模型：{task.selectedModel}</Text>
          {status === 'completed' && task.aiResult && (
            <Button
              icon={<DownloadOutlined />}
              loading={exporting}
              onClick={handleExportExcel}
            >
              导出Excel
            </Button>
          )}
          {!isProcessing && (
            <Button
              type="primary"
              icon={<ReloadOutlined />}
              loading={reReviewing}
              onClick={handleReReview}
            >
              重新审查
            </Button>
          )}
        </Space>
      </div>

      <div className="review-workspace">
        {/* Left: Summary + Log */}
        <div className="workspace-left">
          <div className="workspace-header">
            <h3>审查概要</h3>
          </div>
          <div className="workspace-content">
            <Card size="small" style={{ marginBottom: 12 }}>
              <Space direction="vertical" style={{ width: '100%' }}>
                {overallScore !== undefined && (
                  <div>综合评分：<Text strong>{overallScore}</Text> 分</div>
                )}
                {totalChunks !== undefined && (
                  <div>文档分块数：<Text strong>{totalChunks}</Text></div>
                )}
                <div>发现问题数：<Text strong>{issues.length}</Text></div>
              </Space>
            </Card>
            {task.failReason && (
              <Card size="small" style={{ borderColor: '#ff4d4f', marginBottom: 12 }}>
                <Text type="danger">失败原因：{task.failReason}</Text>
              </Card>
            )}

            {/* Progress bar for processing tasks */}
            {isProcessing && wsProgress > 0 && (
              <Card size="small" style={{ marginBottom: 12 }}>
                <div style={{ marginBottom: 4 }}>
                  <Text type="secondary">
                    <LoadingOutlined spin style={{ marginRight: 6 }} />
                    审查进度
                  </Text>
                </div>
                <Progress
                  percent={wsProgress}
                  status="active"
                  strokeColor={{ '0%': '#108ee9', '100%': '#87d068' }}
                />
              </Card>
            )}

            {/* Real-time Log Window */}
            <Card
              size="small"
              title={
                <Space>
                  {isProcessing ? <LoadingOutlined spin style={{ color: '#1677ff' }} /> : <InfoCircleOutlined />}
                  <span>审查日志</span>
                  <Tag color={isProcessing ? 'processing' : logs.some(l => l.level === 'error') ? 'error' : 'default'} style={{ marginLeft: 4 }}>
                    {logs.length} 条
                  </Tag>
                </Space>
              }
              style={{ marginBottom: 12 }}
              styles={{ body: { padding: 0 } }}
            >
              <div
                className="log-window"
                style={{
                  height: 240,
                  overflowY: 'auto',
                  background: '#fafafa',
                  padding: '4px 0',
                }}
              >
                {logs.length === 0 ? (
                  <div style={{ color: '#bfbfbf', padding: '40px 0', textAlign: 'center', fontSize: 13 }}>
                    <InfoCircleOutlined style={{ fontSize: 24, marginBottom: 8, display: 'block' }} />
                    等待审查日志...
                  </div>
                ) : (
                  logs.map((log, idx) => {
                    const style = LOG_LEVEL_STYLES[log.level] || LOG_LEVEL_STYLES.info;
                    return (
                      <div
                        key={idx}
                        style={{
                          padding: '4px 12px',
                          fontSize: 13,
                          lineHeight: 1.7,
                          borderLeft: `3px solid ${style.color}`,
                          background: idx % 2 === 0 ? '#fff' : '#fafafa',
                          display: 'flex',
                          alignItems: 'flex-start',
                          gap: 8,
                        }}
                      >
                        <span style={{ color: '#8c8c8c', flexShrink: 0, fontSize: 12, fontFamily: 'monospace', marginTop: 1 }}>
                          {log.time}
                        </span>
                        <span style={{ color: style.color, flexShrink: 0, marginTop: 2 }}>
                          {LOG_LEVEL_ICONS[log.level]}
                        </span>
                        <span style={{ color: '#262626', flex: 1 }}>
                          {log.message}
                          {log.progress !== undefined && (
                            <span style={{ color: '#8c8c8c', marginLeft: 6 }}>({log.progress}%)</span>
                          )}
                        </span>
                      </div>
                    );
                  })
                )}
                <div ref={logEndRef} />
              </div>
            </Card>
          </div>
        </div>

        {/* Right: AI Review Results */}
        <div className="workspace-right">
          <div className="workspace-header">
            <h3>审查结果</h3>
          </div>

          {issues.length > 0 && (
            <div className="findings-summary">
              <div className="summary-item">
                <ExclamationCircleOutlined style={{ color: '#f5222d' }} />
                <span>{issues.filter((i) => (i.severity as string) === 'high').length} 高</span>
              </div>
              <div className="summary-item">
                <WarningOutlined style={{ color: '#fa8c16' }} />
                <span>{issues.filter((i) => (i.severity as string) === 'medium').length} 中</span>
              </div>
              <div className="summary-item">
                <CheckCircleOutlined style={{ color: '#52c41a' }} />
                <span>{issues.filter((i) => (i.severity as string) === 'low').length} 低</span>
              </div>
            </div>
          )}

          <div className="findings-list">
            {issues.length === 0 ? (
              <div style={{ padding: 16 }}>
                {task.aiResult ? (
                  <Card size="small" style={{ background: '#fafafa' }}>
                    <Paragraph>
                      <pre style={{ whiteSpace: 'pre-wrap', margin: 0, fontSize: 13, maxHeight: 600, overflow: 'auto' }}>
                        {JSON.stringify(task.aiResult, null, 2)}
                      </pre>
                    </Paragraph>
                  </Card>
                ) : isProcessing ? (
                  <div style={{ textAlign: 'center', marginTop: 60 }}>
                    <Spin size="large" />
                    <div style={{ marginTop: 16 }}>
                      <Text type="secondary">AI 正在审查中，请查看左侧日志面板...</Text>
                    </div>
                  </div>
                ) : (
                  <Empty description="暂无审查结果" style={{ marginTop: 40 }} />
                )}
              </div>
            ) : (
              issues.map((issue, idx) => {
                const severity = String(issue.severity || 'low');
                const category = issue.category ? String(issue.category) : '';
                const explanation = issue.explanation ? String(issue.explanation) : '';
                const originalText = issue.originalText ? String(issue.originalText) : '';
                const suggestion = issue.suggestion ? String(issue.suggestion) : '';
                return (
                  <Card
                    key={idx}
                    size="small"
                    className={`finding-card severity-${severity}`}
                    style={{ marginBottom: 8 }}
                  >
                    <Space style={{ marginBottom: 4 }}>
                      <Tag color={
                        severity === 'high' ? 'red' :
                        severity === 'medium' ? 'orange' : 'green'
                      }>
                        {severity === 'high' ? '高' :
                         severity === 'medium' ? '中' : '低'}
                      </Tag>
                      {category && <Tag>{category}</Tag>}
                    </Space>
                    {explanation && (
                      <Paragraph style={{ marginBottom: 4, fontSize: 13 }}>
                        {explanation}
                      </Paragraph>
                    )}
                    {originalText && (
                      <div className="original-text">
                        <Text type="secondary" style={{ fontSize: 12 }}>原文：</Text>
                        {originalText}
                      </div>
                    )}
                    {suggestion && (
                      <div className="suggestion-text">
                        <Text type="secondary" style={{ fontSize: 12 }}>建议：</Text>
                        {suggestion}
                      </div>
                    )}
                  </Card>
                );
              })
            )}
          </div>
        </div>
      </div>
    </div>
  );
}

export default ReviewWorkspacePage;
