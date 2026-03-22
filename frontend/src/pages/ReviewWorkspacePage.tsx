import { useState, useEffect, useCallback } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import {
  Button,
  Tag,
  Typography,
  Spin,
  Empty,
  Space,
  Badge,
  Select,
  message,
  Tooltip,
} from 'antd';
import {
  ArrowLeftOutlined,
  FilterOutlined,
  CheckCircleOutlined,
  WarningOutlined,
  ExclamationCircleOutlined,
} from '@ant-design/icons';
import mammoth from 'mammoth';
import { getReviewDetail, updateFindingStatus, ReviewTask, ReviewFinding } from '../api/reviews';
import ReviewResultCard from '../components/ReviewResultCard';
import useReviewStore from '../store/reviewStore';
import { SEVERITY_TAG_COLORS, SEVERITY_LABELS, STATUS_LABELS } from '../utils/constants';
import '../styles/reviewWorkspace.css';

const { Title, Text } = Typography;

function ReviewWorkspacePage() {
  const { taskId } = useParams<{ taskId: string }>();
  const navigate = useNavigate();
  const [loading, setLoading] = useState(true);
  const [severityFilter, setSeverityFilter] = useState<string | undefined>();
  const { currentTask, setCurrentTask, documentHtml, setDocumentHtml, updateFindingStatus: updateLocalStatus } = useReviewStore();

  const fetchDetail = useCallback(async () => {
    if (!taskId) return;
    setLoading(true);
    try {
      const res = await getReviewDetail(taskId);
      const task = res.data.data;
      setCurrentTask(task);

      // Parse Word doc if we have document content as base64 or URL
      if (task.documentContent) {
        try {
          // Assume documentContent is base64-encoded .docx
          const binary = atob(task.documentContent);
          const bytes = new Uint8Array(binary.length);
          for (let i = 0; i < binary.length; i++) {
            bytes[i] = binary.charCodeAt(i);
          }
          const result = await mammoth.convertToHtml({ arrayBuffer: bytes.buffer });
          setDocumentHtml(result.value);
        } catch {
          // Fallback: treat as HTML string
          setDocumentHtml(task.documentContent);
        }
      }
    } catch {
      // handled
    } finally {
      setLoading(false);
    }
  }, [taskId, setCurrentTask, setDocumentHtml]);

  useEffect(() => {
    fetchDetail();
    return () => {
      useReviewStore.getState().reset();
    };
  }, [fetchDetail]);

  const handleAccept = async (findingId: number) => {
    if (!taskId) return;
    try {
      await updateFindingStatus(taskId, findingId, 'accepted');
      updateLocalStatus(findingId, 'accepted');
      message.success('已采纳');
    } catch {
      // handled
    }
  };

  const handleReject = async (findingId: number) => {
    if (!taskId) return;
    try {
      await updateFindingStatus(taskId, findingId, 'rejected');
      updateLocalStatus(findingId, 'rejected');
      message.success('已忽略');
    } catch {
      // handled
    }
  };

  const filteredFindings = (currentTask?.findings || []).filter((f: ReviewFinding) => {
    if (!severityFilter) return true;
    return f.severity === severityFilter;
  });

  const severityCounts = {
    high: (currentTask?.findings || []).filter((f: ReviewFinding) => f.severity === 'high').length,
    medium: (currentTask?.findings || []).filter((f: ReviewFinding) => f.severity === 'medium').length,
    low: (currentTask?.findings || []).filter((f: ReviewFinding) => f.severity === 'low').length,
  };

  if (loading) {
    return (
      <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', height: '60vh' }}>
        <Spin size="large" tip="加载中..." />
      </div>
    );
  }

  if (!currentTask) {
    return (
      <Empty description="未找到审查任务" style={{ marginTop: 100 }}>
        <Button type="primary" onClick={() => navigate('/dashboard')}>
          返回工作台
        </Button>
      </Empty>
    );
  }

  return (
    <div>
      <div className="page-header" style={{ marginBottom: 12 }}>
        <Space>
          <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/dashboard')}>
            返回
          </Button>
          <Title level={5} style={{ margin: 0 }}>{currentTask.fileName}</Title>
          <Tag color={currentTask.status === 'completed' ? 'success' : 'processing'}>
            {STATUS_LABELS[currentTask.status]}
          </Tag>
        </Space>
        <Text type="secondary">
          场景：{currentTask.scenarioName} | 模型：{currentTask.modelName}
        </Text>
      </div>

      <div className="review-workspace">
        {/* Left: Document Content */}
        <div className="workspace-left">
          <div className="workspace-header">
            <h3>文档内容</h3>
          </div>
          <div className="workspace-content">
            {documentHtml ? (
              <div
                className="document-preview"
                dangerouslySetInnerHTML={{ __html: documentHtml }}
              />
            ) : (
              <Empty description="暂无文档内容" />
            )}
          </div>
        </div>

        {/* Right: AI Review Results */}
        <div className="workspace-right">
          <div className="workspace-header">
            <h3>审查结果</h3>
            <Badge count={currentTask.findings?.length || 0} overflowCount={999}>
              <span />
            </Badge>
          </div>

          <div className="findings-summary">
            <Tooltip title="高风险">
              <div className="summary-item">
                <ExclamationCircleOutlined style={{ color: '#f5222d' }} />
                <span>{severityCounts.high}</span>
              </div>
            </Tooltip>
            <Tooltip title="中风险">
              <div className="summary-item">
                <WarningOutlined style={{ color: '#fa8c16' }} />
                <span>{severityCounts.medium}</span>
              </div>
            </Tooltip>
            <Tooltip title="低风险">
              <div className="summary-item">
                <CheckCircleOutlined style={{ color: '#52c41a' }} />
                <span>{severityCounts.low}</span>
              </div>
            </Tooltip>
          </div>

          <div className="workspace-toolbar">
            <FilterOutlined />
            <Select
              placeholder="按严重程度筛选"
              allowClear
              size="small"
              style={{ width: 160 }}
              value={severityFilter}
              onChange={setSeverityFilter}
              options={[
                { label: '高', value: 'high' },
                { label: '中', value: 'medium' },
                { label: '低', value: 'low' },
              ]}
            />
          </div>

          <div className="findings-list">
            {filteredFindings.length === 0 ? (
              <Empty description="暂无审查发现" style={{ marginTop: 40 }} />
            ) : (
              filteredFindings.map((finding: ReviewFinding) => (
                <ReviewResultCard
                  key={finding.id}
                  finding={finding}
                  onAccept={handleAccept}
                  onReject={handleReject}
                />
              ))
            )}
          </div>
        </div>
      </div>
    </div>
  );
}

export default ReviewWorkspacePage;
