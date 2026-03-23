import { useState, useEffect, useCallback } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import {
  Button,
  Tag,
  Typography,
  Spin,
  Empty,
  Space,
  Card,
} from 'antd';
import {
  ArrowLeftOutlined,
  CheckCircleOutlined,
  WarningOutlined,
  ExclamationCircleOutlined,
} from '@ant-design/icons';
import { getReviewDetail, ReviewTask } from '../api/reviews';
import { STATUS_LABELS } from '../utils/constants';
import '../styles/reviewWorkspace.css';

const { Title, Text, Paragraph } = Typography;

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

function ReviewWorkspacePage() {
  const { taskId } = useParams<{ taskId: string }>();
  const navigate = useNavigate();
  const [loading, setLoading] = useState(true);
  const [task, setTask] = useState<ReviewTask | null>(null);

  const fetchDetail = useCallback(async () => {
    if (!taskId) return;
    setLoading(true);
    try {
      const res = await getReviewDetail(taskId);
      setTask(res.data.data);
    } catch {
      // handled
    } finally {
      setLoading(false);
    }
  }, [taskId]);

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

  return (
    <div>
      <div className="page-header" style={{ marginBottom: 12 }}>
        <Space>
          <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/dashboard')}>
            返回
          </Button>
          <Title level={5} style={{ margin: 0 }}>{task.fileName}</Title>
          <Tag color={status === 'completed' ? 'success' : status === 'failed' ? 'error' : 'processing'}>
            {STATUS_LABELS[status] || task.status}
          </Tag>
        </Space>
        <Text type="secondary">
          模型：{task.selectedModel}
        </Text>
      </div>

      <div className="review-workspace">
        {/* Left: Summary */}
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
              <Card size="small" style={{ borderColor: '#ff4d4f' }}>
                <Text type="danger">失败原因：{task.failReason}</Text>
              </Card>
            )}
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
