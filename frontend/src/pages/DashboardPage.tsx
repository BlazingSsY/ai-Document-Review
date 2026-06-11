import { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Card,
  Row,
  Col,
  Table,
  Tag,
  Button,
  Space,
  Statistic,
  Select,
  Tabs,
  Typography,
  Modal,
  Form,
  Progress,
  Alert,
  message,
  Popconfirm,
} from 'antd';
import {
  FileTextOutlined,
  CheckCircleOutlined,
  SyncOutlined,
  PlusOutlined,
  ThunderboltOutlined,
  StopOutlined,
  RedoOutlined,
  DeleteOutlined,
  DeploymentUnitOutlined,
  BookOutlined,
} from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import type { ReviewTask, ReviewMode } from '../api/reviews';
import type { Scenario } from '../api/scenarios';
import { getEnabledModels, AIModel } from '../api/models';
import {
  getReviewApi, getScenarioApi, getUnifiedReviewList, getUnifiedReviewStats,
  PIPELINE_LABEL, PIPELINE_COLOR, type UnifiedStats,
} from '../api/pipelineApi';
import { STATUS_LABELS, STATUS_COLORS, PAGE_SIZE } from '../utils/constants';
import FileUploader from '../components/FileUploader';
import taskWebSocket, { TaskProgressMessage } from '../utils/websocket';

const { Title, Text } = Typography;

type ModeFilter = 'ALL' | ReviewMode;

const EMPTY_STATS: UnifiedStats = {
  total: 0,
  completed: 0,
  processing: 0,
  failed: 0,
  todayCount: 0,
  byMode: {
    CHUNK: { total: 0, completed: 0, processing: 0, failed: 0, todayCount: 0 },
    RAG: { total: 0, completed: 0, processing: 0, failed: 0, todayCount: 0 },
  },
};

function DashboardPage() {
  const navigate = useNavigate();
  const [tasks, setTasks] = useState<ReviewTask[]>([]);
  const [loading, setLoading] = useState(false);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [statusFilter, setStatusFilter] = useState<string | undefined>();
  const [modeFilter, setModeFilter] = useState<ModeFilter>('ALL');
  const [stats, setStats] = useState<UnifiedStats>(EMPTY_STATS);

  // Review creation modal state
  const [reviewModalOpen, setReviewModalOpen] = useState(false);
  // 决策 #8：无默认管线，强制用户在 Tab 切换器上手动选择。
  const [draftMode, setDraftMode] = useState<ReviewMode | undefined>();
  const [currentStep, setCurrentStep] = useState(0);
  const [selectedFile, setSelectedFile] = useState<File | null>(null);
  const [scenarios, setScenarios] = useState<Scenario[]>([]);
  const [models, setModels] = useState<AIModel[]>([]);
  const [scenarioId, setScenarioId] = useState<number | undefined>();
  const [selectedModel, setSelectedModel] = useState<string | undefined>();
  const [submitting, setSubmitting] = useState(false);
  const [embeddingModelCount, setEmbeddingModelCount] = useState<number | null>(null);

  // Track if there are truly active processing tasks (for spinner)
  const [hasActiveProcessing, setHasActiveProcessing] = useState(false);

  // Per-task progress driven by the global WS feed. Used to render an inline
  // progress bar in the task table so users see retry / first-pass progress
  // without having to open the workspace.
  const [taskProgress, setTaskProgress] = useState<Record<string, number>>({});

  const fetchTasks = async () => {
    setLoading(true);
    try {
      const res = await getUnifiedReviewList({
        page,
        pageSize: PAGE_SIZE,
        mode: modeFilter === 'ALL' ? 'ALL' : modeFilter,
        status: statusFilter,
      });
      const data = res.data.data;
      setTasks(data.records || []);
      setTotal(data.total);
    } catch {
      // handled by interceptor
    } finally {
      setLoading(false);
    }
  };

  const fetchStats = async () => {
    try {
      const res = await getUnifiedReviewStats();
      const s = res.data.data;
      setStats(s);
      setHasActiveProcessing(s.processing > 0);
    } catch {
      // handled
    }
  };

  useEffect(() => {
    fetchStats();
    // Connect websocket for global task updates
    taskWebSocket.connect();
    const globalHandler = (data: TaskProgressMessage) => {
      const s = data.status?.toUpperCase();
      if (s === 'COMPLETED' || s === 'FAILED' || s === 'CANCELLED') {
        // Terminal state — drop per-task progress so the bar doesn't linger after the row flips.
        setTaskProgress((prev) => {
          if (!(data.taskId in prev)) return prev;
          const next = { ...prev };
          delete next[data.taskId];
          return next;
        });
        fetchStats();
        fetchTasks();
      } else if (data.taskId && typeof data.progress === 'number') {
        setTaskProgress((prev) => {
          // Progress should be monotonic — guard against stale frames arriving out of order.
          const current = prev[data.taskId] ?? 0;
          if (data.progress <= current) return prev;
          return { ...prev, [data.taskId]: data.progress };
        });
      }
    };
    taskWebSocket.subscribe('*', globalHandler);
    return () => {
      taskWebSocket.unsubscribe('*', globalHandler);
    };
  }, []);

  useEffect(() => {
    fetchTasks();
  }, [page, statusFilter, modeFilter]);

  // When the user switches the Tab in the create-review modal, reload the scenario
  // list from that pipeline (CHUNK or RAG) and the chat-model list (shared, but we
  // re-fetch alongside so the dropdown stays in sync). For RAG we also check that
  // at least one embedding model is enabled — RAG can't run without one.
  useEffect(() => {
    if (!reviewModalOpen || !draftMode) {
      setScenarios([]);
      setModels([]);
      setScenarioId(undefined);
      setSelectedModel(undefined);
      setEmbeddingModelCount(null);
      return;
    }
    const fetchData = async () => {
      try {
        const scenarioApi = getScenarioApi(draftMode);
        const fetches: Promise<unknown>[] = [
          scenarioApi.getScenarioList({ page: 1, pageSize: 1000 }),
          getEnabledModels('chat'),
        ];
        if (draftMode === 'RAG') {
          fetches.push(getEnabledModels('embedding'));
        }
        const results = await Promise.all(fetches);
        const scenRes = results[0] as Awaited<ReturnType<typeof scenarioApi.getScenarioList>>;
        const modelRes = results[1] as Awaited<ReturnType<typeof getEnabledModels>>;
        setScenarios(scenRes.data.data.records);
        setModels(modelRes.data.data);
        if (draftMode === 'RAG') {
          const embedRes = results[2] as Awaited<ReturnType<typeof getEnabledModels>>;
          setEmbeddingModelCount(embedRes.data.data.length);
        }
      } catch {
        // handled
      }
    };
    fetchData();
  }, [reviewModalOpen, draftMode]);

  const handleSubmit = async () => {
    if (!draftMode) { message.warning('请选择审查方式'); return; }
    if (!selectedFile) { message.warning('请先上传文件'); return; }
    if (!scenarioId) { message.warning('请选择审查场景'); return; }
    if (!selectedModel) { message.warning('请选择 AI 模型'); return; }
    if (draftMode === 'RAG' && embeddingModelCount === 0) {
      message.error('RAG 审查需要至少启用一个 embedding 模型，请先到「模型管理」配置');
      return;
    }

    setSubmitting(true);
    try {
      const formData = new FormData();
      formData.append('file', selectedFile);
      formData.append('scenarioId', String(scenarioId));
      formData.append('selectedModel', selectedModel);
      const reviewApi = getReviewApi(draftMode);
      await reviewApi.submitReview(formData);
      message.success(`审查任务已提交（${PIPELINE_LABEL[draftMode]}）`);
    } catch {
      setSubmitting(false);
      setReviewModalOpen(false);
      resetReviewModal();
      fetchTasks();
      fetchStats();
      return;
    }

    setSubmitting(false);
    setReviewModalOpen(false);
    resetReviewModal();
    fetchTasks();
    fetchStats();
  };

  /** Pick the right pipeline client based on a task's reviewMode (defaulting to CHUNK
   *  for legacy tasks that pre-date the field — those all belong to chunk side). */
  const apiForTask = (task: ReviewTask) => getReviewApi(task.reviewMode ?? 'CHUNK');

  const handleCancel = async (task: ReviewTask) => {
    try {
      await apiForTask(task).cancelReview(task.id);
      message.success('任务已取消');
      fetchTasks();
      fetchStats();
    } catch { /* handled */ }
  };

  const handleReReview = async (task: ReviewTask) => {
    try {
      await apiForTask(task).reReview(task.id);
      message.success('重新审查任务已提交');
      await fetchTasks();
      fetchStats();
    } catch { /* handled */ }
  };

  const handleDelete = async (task: ReviewTask) => {
    try {
      await apiForTask(task).deleteReview(task.id);
      message.success('任务已删除');
      fetchTasks();
      fetchStats();
    } catch { /* handled */ }
  };

  const resetReviewModal = () => {
    setDraftMode(undefined);
    setCurrentStep(0);
    setSelectedFile(null);
    setScenarioId(undefined);
    setSelectedModel(undefined);
    setEmbeddingModelCount(null);
  };

  const columns: ColumnsType<ReviewTask> = [
    {
      title: '文件名',
      dataIndex: 'fileName',
      key: 'fileName',
      ellipsis: true,
      width: 200,
    },
    {
      title: '审查方式',
      dataIndex: 'reviewMode',
      key: 'reviewMode',
      width: 120,
      render: (mode: ReviewMode | undefined) => {
        // 没有 reviewMode 的历史任务统一显示为 CHUNK（迁移脚本会按 ai_result.reviewMode
        // 推断填入；推断不出来的就是 chunk）。
        const m = mode ?? 'CHUNK';
        return <Tag color={PIPELINE_COLOR[m]}>{PIPELINE_LABEL[m]}</Tag>;
      },
    },
    {
      title: 'AI 模型',
      dataIndex: 'selectedModel',
      key: 'selectedModel',
      width: 150,
    },
    {
      title: '状态',
      key: 'status',
      width: 160,
      render: (_, record) => {
        const s = record.status?.toLowerCase();
        const tag = <Tag color={STATUS_COLORS[s]}>{STATUS_LABELS[s] || record.status}</Tag>;
        if (s !== 'processing') return tag;
        const pct = taskProgress[record.id];
        if (pct === undefined) return tag;
        return (
          <Space direction="vertical" size={2} style={{ width: '100%' }}>
            {tag}
            <Progress
              percent={pct}
              size="small"
              status="active"
              showInfo
              strokeColor={{ '0%': '#108ee9', '100%': '#87d068' }}
            />
          </Space>
        );
      },
    },
    {
      title: '发现问题',
      key: 'issueCount',
      width: 100,
      render: (_, record) => {
        const issues = record.aiResult?.allIssues;
        return Array.isArray(issues) ? issues.length : '-';
      },
    },
    {
      title: '创建时间',
      dataIndex: 'createdAt',
      key: 'createdAt',
      width: 180,
      render: (text: string) => text ? new Date(text).toLocaleString('zh-CN') : '-',
    },
    {
      title: '操作',
      key: 'action',
      width: 280,
      render: (_, record) => {
        const s = record.status?.toUpperCase();
        return (
          <Space>
            <Button
              type="link"
              size="small"
              onClick={() => navigate(`/review/${record.id}`)}
              disabled={s === 'PENDING'}
            >
              查看详情
            </Button>
            {(s === 'COMPLETED' || s === 'FAILED' || s === 'CANCELLED') && (
              <Popconfirm
                title="确定要重新审查此任务吗？"
                onConfirm={() => { handleReReview(record); }}
                okText="确定"
                cancelText="取消"
              >
                <Button type="link" size="small" icon={<RedoOutlined />}>
                  重新审查
                </Button>
              </Popconfirm>
            )}
            {(s === 'PENDING' || s === 'PROCESSING') && (
              <Popconfirm
                title="确定要取消此审查任务吗？"
                onConfirm={() => { handleCancel(record); }}
                okText="确定"
                cancelText="取消"
              >
                <Button type="link" size="small" danger icon={<StopOutlined />}>
                  取消
                </Button>
              </Popconfirm>
            )}
            {s !== 'PROCESSING' && (
              <Popconfirm
                title="确定要删除此任务吗？删除后不可恢复。"
                onConfirm={() => { handleDelete(record); }}
                okText="确定"
                cancelText="取消"
              >
                <Button type="link" size="small" danger icon={<DeleteOutlined />}>
                  删除
                </Button>
              </Popconfirm>
            )}
          </Space>
        );
      },
    },
  ];

  return (
    <div>
      <div className="page-header">
        <Title level={4} style={{ margin: 0 }}>工作台</Title>
        <Button
          type="primary"
          icon={<PlusOutlined />}
          onClick={() => { resetReviewModal(); setReviewModalOpen(true); }}
        >
          新建审查
        </Button>
      </div>

      <Row gutter={16} style={{ marginBottom: 24 }}>
        <Col xs={12} sm={6}>
          <Card className="stat-card stat-card-blue">
            <Statistic
              title="审查总数"
              value={stats.total}
              prefix={<FileTextOutlined style={{ color: '#1677ff' }} />}
            />
          </Card>
        </Col>
        <Col xs={12} sm={6}>
          <Card className="stat-card stat-card-green">
            <Statistic
              title="已完成"
              value={stats.completed}
              prefix={<CheckCircleOutlined style={{ color: '#52c41a' }} />}
            />
          </Card>
        </Col>
        <Col xs={12} sm={6}>
          <Card className="stat-card stat-card-purple">
            <Statistic
              title="进行中"
              value={stats.processing}
              prefix={<SyncOutlined spin={hasActiveProcessing} style={{ color: '#722ed1' }} />}
            />
          </Card>
        </Col>
        <Col xs={12} sm={6}>
          <Card className="stat-card stat-card-orange">
            <Statistic
              title="今日审查"
              value={stats.todayCount}
              prefix={<ThunderboltOutlined style={{ color: '#faad14' }} />}
            />
          </Card>
        </Col>
      </Row>

      <Card>
        <div style={{ marginBottom: 16, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
          <Title level={5} style={{ margin: 0 }}>审查任务列表</Title>
          <Space>
            <Select
              style={{ width: 160 }}
              value={modeFilter}
              onChange={(v) => { setModeFilter(v); setPage(1); }}
              options={[
                { label: '全部审查方式', value: 'ALL' },
                { label: '智能召回审查', value: 'RAG' },
                { label: '全文逐章审查', value: 'CHUNK' },
              ]}
            />
            <Select
              placeholder="状态筛选"
              allowClear
              style={{ width: 140 }}
              value={statusFilter}
              onChange={(v) => { setStatusFilter(v); setPage(1); }}
              options={[
                { label: '待处理', value: 'pending' },
                { label: '处理中', value: 'processing' },
                { label: '已完成', value: 'completed' },
                { label: '失败', value: 'failed' },
                { label: '已取消', value: 'cancelled' },
              ]}
            />
            <Button onClick={() => { fetchTasks(); fetchStats(); }}>刷新</Button>
          </Space>
        </div>
        <Table
          columns={columns}
          dataSource={tasks}
          rowKey="id"
          loading={loading}
          pagination={{
            current: page,
            pageSize: PAGE_SIZE,
            total,
            showTotal: (t) => `共 ${t} 条`,
            onChange: (p) => setPage(p),
            showSizeChanger: false,
          }}
        />
      </Card>

      <Row gutter={16} style={{ marginTop: 16 }}>
        <Col xs={12}>
          <Card
            hoverable
            onClick={() => navigate('/rag/rules')}
            style={{ textAlign: 'center', cursor: 'pointer' }}
          >
            <DeploymentUnitOutlined style={{ fontSize: 32, color: '#722ed1', marginBottom: 8 }} />
            <div style={{ fontWeight: 500 }}>智能召回审查 · 规则与场景</div>
            <div style={{ color: '#8c8c8c', fontSize: 13 }}>原子检查项 + 向量召回管线</div>
          </Card>
        </Col>
        <Col xs={12}>
          <Card
            hoverable
            onClick={() => navigate('/chunk/rules')}
            style={{ textAlign: 'center', cursor: 'pointer' }}
          >
            <BookOutlined style={{ fontSize: 32, color: '#1677ff', marginBottom: 8 }} />
            <div style={{ fontWeight: 500 }}>全文逐章审查 · 规则与场景</div>
            <div style={{ color: '#8c8c8c', fontSize: 13 }}>按章节切片 + 批处理审查管线</div>
          </Card>
        </Col>
      </Row>

      {/* New Review Modal — top Tabs select pipeline; content below mirrors the
          previous 2-step flow (file → scenario+model) but is gated on a Tab choice. */}
      <Modal
        title="新建文件审查"
        open={reviewModalOpen}
        onCancel={() => {
          setReviewModalOpen(false);
          resetReviewModal();
        }}
        footer={null}
        destroyOnClose
        width={680}
      >
        <Tabs
          activeKey={draftMode ?? ''}
          onChange={(key) => {
            setDraftMode(key as ReviewMode);
            // 切换 Tab 时把已经填的场景/模型清空，避免选了另一个管线的项后串库。
            setScenarioId(undefined);
            setSelectedModel(undefined);
            setCurrentStep(0);
          }}
          items={[
            {
              key: 'RAG',
              label: <span><DeploymentUnitOutlined /> 智能召回审查</span>,
            },
            {
              key: 'CHUNK',
              label: <span><BookOutlined /> 全文逐章审查</span>,
            },
          ]}
        />
        {!draftMode && (
          <Alert
            type="info"
            showIcon
            message="请先在上方选择审查方式"
            description="智能召回审查（向量检索 + 原子检查）和 全文逐章审查（按章节切片 + 批处理）使用不同的规则库与场景，互不可见。"
          />
        )}

        {draftMode && draftMode === 'RAG' && embeddingModelCount === 0 && (
          <Alert
            type="warning"
            showIcon
            style={{ marginBottom: 16 }}
            message="尚未启用 embedding 模型"
            description="智能召回审查需要至少一个启用的 embedding 模型；请到「模型管理」配置。reranker 模型可选，若未配置则按召回分数顺序使用。"
          />
        )}

        {draftMode && currentStep === 0 && (
          <div>
            <FileUploader onFileSelect={(file) => setSelectedFile(file)} />
            {selectedFile && (
              <div style={{ marginTop: 16, textAlign: 'center' }}>
                <Text>
                  已选择文件：<Text strong>{selectedFile.name}</Text>
                  （{(selectedFile.size / 1024 / 1024).toFixed(2)} MB）
                </Text>
              </div>
            )}
            <div style={{ marginTop: 24, textAlign: 'center' }}>
              <Button
                type="primary"
                disabled={!selectedFile}
                onClick={() => setCurrentStep(1)}
              >
                下一步
              </Button>
            </div>
          </div>
        )}

        {draftMode && currentStep === 1 && (
          <div>
            <Form layout="vertical">
              <Form.Item
                label={`审查场景（${PIPELINE_LABEL[draftMode]}）`}
                required
              >
                <Select
                  placeholder={`请选择${PIPELINE_LABEL[draftMode]}场景`}
                  value={scenarioId}
                  onChange={setScenarioId}
                  options={scenarios.map((s) => ({
                    label: s.name,
                    value: s.id,
                  }))}
                  showSearch
                  filterOption={(input, option) =>
                    (option?.label as string)?.toLowerCase().includes(input.toLowerCase())
                  }
                />
              </Form.Item>
              <Form.Item label="AI 对话模型" required>
                <Select
                  placeholder="请选择 chat 模型"
                  value={selectedModel}
                  onChange={setSelectedModel}
                  options={models.map((m) => ({
                    label: `${m.name} (${m.provider})`,
                    value: m.name,
                  }))}
                />
              </Form.Item>
            </Form>
            <div style={{ textAlign: 'center' }}>
              <Space size="middle">
                <Button onClick={() => setCurrentStep(0)}>上一步</Button>
                <Button type="primary" loading={submitting} onClick={handleSubmit}>
                  提交审查
                </Button>
              </Space>
            </div>
          </div>
        )}

      </Modal>
    </div>
  );
}

export default DashboardPage;
