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
  Steps,
  Typography,
  Modal,
  Form,
  Progress,
  message,
  Popconfirm,
  Switch,
  Tooltip,
  Result,
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
  BookOutlined,
  FileSearchOutlined,
  SafetyCertificateOutlined,
  ArrowLeftOutlined,
  ArrowRightOutlined,
  RocketOutlined,
  PaperClipOutlined,
  LockOutlined,
} from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import type { ReviewTask } from '../../review/api/reviews';
import type { Scenario } from '../../scenarios/api/scenarios';
import { getEnabledModels, AIModel } from '../../modelConfig/api/models';
import {
  getReviewApi, getScenarioApi, getUnifiedReviewList, getUnifiedReviewStats,
  PIPELINE_LABEL, type UnifiedStats,
} from '../../review/api/pipelineApi';
import {
  STATUS_LABELS, STATUS_COLORS, PAGE_SIZE,
  DEFAULT_REVIEW_CATEGORY, reviewCategoryLabel,
} from '../../../shared/utils/constants';
import FileUploader from '../../review/components/FileUploader';
import taskWebSocket, { TaskProgressMessage } from '../../../shared/utils/websocket';
import useLogStore from '../../review/store/logStore';
import useAuthStore from '../../auth/store/authStore';
import '../styles/dashboard.css';

const { Title, Text } = Typography;

const EMPTY_STATS: UnifiedStats = {
  total: 0,
  completed: 0,
  processing: 0,
  failed: 0,
  todayCount: 0,
  byMode: {
    CHUNK: { total: 0, completed: 0, processing: 0, failed: 0, todayCount: 0 },
    SAR: { total: 0, completed: 0, processing: 0, failed: 0, todayCount: 0 },
  },
};

function countReviewProblems(task: ReviewTask): number | '-' {
  // Prefer the backend-cached scalar count so the list never needs the full aiResult.
  if (typeof task.problemCount === 'number') return task.problemCount;
  const checks = task.aiResult?.allCheckResults;
  if (Array.isArray(checks) && checks.length > 0) {
    return checks.filter((item) => {
      if (!item || typeof item !== 'object' || Array.isArray(item)) return false;
      const check = item as Record<string, unknown>;
      const status = String(check.manualStatus || check.status || 'Review');
      return status !== 'Pass' && status !== 'N/A';
    }).length;
  }
  const issues = task.aiResult?.allIssues;
  return Array.isArray(issues) ? issues.length : '-';
}

function DashboardPage() {
  const navigate = useNavigate();
  const user = useAuthStore((state) => state.user);
  const canUseEnvReview = user?.role === 'supervisor'
    || Boolean(user?.featureCodes?.includes('ENV_TEST_OUTLINE_REVIEW'));
  const [tasks, setTasks] = useState<ReviewTask[]>([]);
  const [loading, setLoading] = useState(false);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [statusFilter, setStatusFilter] = useState<string | undefined>();
  const [stats, setStats] = useState<UnifiedStats>(EMPTY_STATS);

  // Review creation modal state — 当前前端只开放全文逐章审查。
  const [reviewModalOpen, setReviewModalOpen] = useState(false);
  const [currentStep, setCurrentStep] = useState(0);
  const [selectedFile, setSelectedFile] = useState<File | null>(null);
  const [scenarios, setScenarios] = useState<Scenario[]>([]);
  const [models, setModels] = useState<AIModel[]>([]);
  const [scenarioId, setScenarioId] = useState<number | undefined>();
  const [selectedModel, setSelectedModel] = useState<string | undefined>();
  const [qualityCheckEnabled, setQualityCheckEnabled] = useState(true);
  const [submitting, setSubmitting] = useState(false);

  // Track if there are truly active processing tasks (for spinner)
  const [hasActiveProcessing, setHasActiveProcessing] = useState(false);

  // Per-task progress driven by the global WS feed. Used to render an inline
  // progress bar in the task table so users see retry / first-pass progress
  // without having to open the workspace.
  const [taskProgress, setTaskProgress] = useState<Record<string, number>>({});

  // 用已知进度回填进度条；已有的实时值优先（prev 覆盖 seeded），避免把更新的进度盖回旧值。
  const seedProgress = (seeded: Record<string, number>) => {
    if (Object.keys(seeded).length > 0) {
      setTaskProgress((prev) => ({ ...seeded, ...prev }));
    }
  };

  const fetchTasks = async () => {
    if (!canUseEnvReview) return;
    setLoading(true);
    try {
      const res = await getUnifiedReviewList({
        page,
        pageSize: PAGE_SIZE,
        mode: 'CHUNK',
        status: statusFilter,
      });
      const data = res.data.data;
      const records = data.records || [];
      setTasks(records);
      setTotal(data.total);
      // 硬刷新场景：内存里的 logStore 已被清空，靠后端返回的 progress 立即回填进度条。
      const seeded: Record<string, number> = {};
      for (const t of records) {
        if (typeof t.progress === 'number') seeded[t.id] = t.progress;
      }
      seedProgress(seeded);
    } catch {
      // handled by interceptor
    } finally {
      setLoading(false);
    }
  };

  const fetchStats = async () => {
    if (!canUseEnvReview) return;
    try {
      const res = await getUnifiedReviewStats();
      const s = res.data.data;
      const visibleStats = s.byMode.CHUNK;
      setStats({ ...s, ...visibleStats });
      setHasActiveProcessing(visibleStats.processing > 0);
    } catch {
      // handled
    }
  };

  useEffect(() => {
    if (!canUseEnvReview) return undefined;
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
  }, [canUseEnvReview]);

  // 从常驻的 logStore 回填每个任务的最近一次进度。
  // taskProgress 是组件本地 state：从详情页返回时本组件被卸载又重挂，state 归零，
  // 导致进度条要干等下一条 WS 帧（间隔可达数秒）才重新出现。logStore 跨页面常驻、
  // 一直在收进度帧，挂载时读它一次就能让进度条立即显示；后续 WS 帧照常单调推进。
  useEffect(() => {
    const logsByTask = useLogStore.getState().logsByTask;
    const seeded: Record<string, number> = {};
    for (const [tid, entries] of Object.entries(logsByTask)) {
      for (let i = entries.length - 1; i >= 0; i--) {
        if (typeof entries[i].progress === 'number') {
          seeded[tid] = entries[i].progress as number;
          break;
        }
      }
    }
    seedProgress(seeded);
  }, []);

  useEffect(() => {
    if (!canUseEnvReview) return;
    fetchTasks();
  }, [page, statusFilter, canUseEnvReview]);

  // 新建审查只加载全文逐章审查的场景和共享对话模型。
  useEffect(() => {
    if (!canUseEnvReview) return;
    if (!reviewModalOpen) {
      setScenarios([]);
      setModels([]);
      setScenarioId(undefined);
      setSelectedModel(undefined);
      return;
    }
    const fetchData = async () => {
      try {
        const scenarioApi = getScenarioApi('CHUNK');
        const [scenarioRes, modelRes] = await Promise.all([
          scenarioApi.getScenarioList({ page: 1, pageSize: 1000 }),
          getEnabledModels('chat'),
        ]);
        setScenarios(scenarioRes.data.data.records);
        setModels(modelRes.data.data);
      } catch {
        // handled by interceptor
      }
    };
    fetchData();
  }, [reviewModalOpen, canUseEnvReview]);

  const handleSubmit = async () => {
    if (!selectedFile) { message.warning('请先上传文件'); return; }
    if (!scenarioId) { message.warning('请选择审查场景'); return; }
    if (!selectedModel) { message.warning('请选择 AI 模型'); return; }
    setSubmitting(true);
    try {
      const formData = new FormData();
      formData.append('file', selectedFile);
      formData.append('scenarioId', String(scenarioId));
      formData.append('selectedModel', selectedModel);
      formData.append('qualityCheckEnabled', String(qualityCheckEnabled));
      formData.append('reviewCategory', DEFAULT_REVIEW_CATEGORY);
      const reviewApi = getReviewApi('CHUNK');
      await reviewApi.submitReview(formData);
      message.success(`审查任务已提交（${PIPELINE_LABEL.CHUNK}）`);
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
    setCurrentStep(0);
    setSelectedFile(null);
    setScenarioId(undefined);
    setSelectedModel(undefined);
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
      // 类别与方式合成一列：类别是业务域（审的是什么），方式是管线（怎么审）——
      // 方式从属于类别，拆成两列既占宽度又让人误以为是并列维度。
      title: '审查类型',
      key: 'reviewType',
      width: 170,
      render: (_: unknown, task: ReviewTask) => {
        // 没有 reviewMode 的历史任务统一显示为 CHUNK（迁移脚本会按 ai_result.reviewMode
        // 推断填入；推断不出来的就是 chunk）。类别侧后端已对空值回落为环境试验大纲。
        const mode = task.reviewMode ?? 'CHUNK';
        return (
          <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'flex-start', gap: 3 }}>
            <Tag color="purple" style={{ marginInlineEnd: 0 }}>
              {reviewCategoryLabel(task.reviewCategory)}
            </Tag>
            {/* 审查方式是从属信息，用次要灰而不是管线主题色：一列里两个高饱和色块
                会互相抢视线，反而看不出主次。 */}
            <span style={{ fontSize: 12, color: '#8c8c8c', paddingLeft: 2 }}>
              {PIPELINE_LABEL[mode]}
            </span>
          </div>
        );
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
      render: (_, record) => countReviewProblems(record),
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

  if (!canUseEnvReview) {
    return (
      <Card>
        <Result
          icon={<LockOutlined style={{ color: '#8c8c8c' }} />}
          title="当前未分配审查功能"
          subTitle="请联系本单位管理员或平台管理员，为您分配“环境试验大纲审查”功能及所需规则库。"
        />
      </Card>
    );
  }

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
        <Col span={24}>
          <Card
            className="review-library-entry"
            hoverable
            onClick={() => navigate('/chunk/rules')}
          >
            <div className="review-library-entry__icon"><BookOutlined /></div>
            <div>
              <div className="review-library-entry__title">全文逐章审查 · 规则与场景</div>
              <div className="review-library-entry__description">管理环境试验大纲的审查场景、规则文件与应用范围</div>
            </div>
            <ArrowRightOutlined className="review-library-entry__arrow" />
          </Card>
        </Col>
      </Row>

      {/* New Review Modal — 当前只开放全文逐章审查，采用两步式任务创建流程。 */}
      <Modal
        className="review-create-modal"
        title={(
          <div className="review-create-modal__title">
            <span className="review-create-modal__title-icon"><FileSearchOutlined /></span>
            <div className="review-create-modal__title-copy">
              <div className="review-create-modal__title-line">
                <span className="review-create-modal__title-text">新建文件审查</span>
                <Tag color="blue">环境试验大纲</Tag>
                <Tag icon={<BookOutlined />}>全文逐章审查</Tag>
              </div>
              <div className="review-create-modal__title-sub">
                上传环境试验大纲，系统将按章节自动拆分并执行规则审查
              </div>
            </div>
          </div>
        )}
        open={reviewModalOpen}
        onCancel={() => {
          if (submitting) return;
          setReviewModalOpen(false);
          resetReviewModal();
        }}
        footer={currentStep === 0 ? (
          <>
            <Button
              onClick={() => {
                setReviewModalOpen(false);
                resetReviewModal();
              }}
            >
              取消
            </Button>
            <Button
              type="primary"
              icon={<ArrowRightOutlined />}
              iconPosition="end"
              disabled={!selectedFile}
              onClick={() => setCurrentStep(1)}
            >
              下一步
            </Button>
          </>
        ) : (
          <>
            <Button
              icon={<ArrowLeftOutlined />}
              disabled={submitting}
              onClick={() => setCurrentStep(0)}
            >
              返回
            </Button>
            <Button
              type="primary"
              icon={<RocketOutlined />}
              loading={submitting}
              disabled={!scenarioId || !selectedModel}
              onClick={handleSubmit}
            >
              开始审查
            </Button>
          </>
        )}
        closable={!submitting}
        maskClosable={!submitting}
        destroyOnClose
        centered
        width={600}
      >
        <Steps
          className="review-create-modal__steps"
          size="small"
          current={currentStep}
          responsive={false}
          items={[
            { title: '上传文档' },
            { title: '审查配置' },
          ]}
        />

        {currentStep === 0 && (
          <div className="review-create-stage">
            <div className="review-create-upload">
              <FileUploader
                onFileSelect={setSelectedFile}
                onFileRemove={() => setSelectedFile(null)}
                description="支持 .docx / .doc 格式，单个文件不超过 20 MB"
              />
            </div>

            {selectedFile && (
              <div className="review-create-file">
                <span className="review-create-file__icon"><PaperClipOutlined /></span>
                <div className="review-create-file__meta">
                  <Text strong ellipsis>{selectedFile.name}</Text>
                  <Text type="secondary">{(selectedFile.size / 1024 / 1024).toFixed(2)} MB</Text>
                </div>
                <Tag color="success">已就绪</Tag>
              </div>
            )}
          </div>
        )}

        {currentStep === 1 && (
          <div className="review-create-stage">
            {selectedFile && (
              <div className="review-create-file">
                <span className="review-create-file__icon"><PaperClipOutlined /></span>
                <div className="review-create-file__meta">
                  <Text strong ellipsis>{selectedFile.name}</Text>
                  <Text type="secondary">{(selectedFile.size / 1024 / 1024).toFixed(2)} MB</Text>
                </div>
                <Button type="link" size="small" onClick={() => setCurrentStep(0)}>更换文件</Button>
              </div>
            )}

            <Form className="review-create-form" layout="vertical" requiredMark={false}>
              <Row gutter={16}>
                <Col xs={24} md={12}>
                  <Form.Item label="审查场景" required>
                    <Select
                      placeholder="请选择审查场景"
                      value={scenarioId}
                      onChange={setScenarioId}
                      options={scenarios.map((scenario) => ({
                        label: scenario.name,
                        value: scenario.id,
                      }))}
                      showSearch
                      filterOption={(input, option) =>
                        (option?.label as string)?.toLowerCase().includes(input.toLowerCase())
                      }
                    />
                  </Form.Item>
                </Col>
                <Col xs={24} md={12}>
                  <Form.Item label="AI 对话模型" required>
                    <Select
                      placeholder="请选择 AI 模型"
                      value={selectedModel}
                      onChange={setSelectedModel}
                      options={models.map((model) => ({
                        label: `${model.name} (${model.provider})`,
                        value: model.name,
                      }))}
                    />
                  </Form.Item>
                </Col>
              </Row>

              <div className="review-create-quality">
                <span className="review-create-quality__icon"><SafetyCertificateOutlined /></span>
                <div className="review-create-quality__copy">
                  <Text strong>全文质量检查</Text>
                  <Text type="secondary">
                    {qualityCheckEnabled
                      ? '检查错别字、语病、术语一致性及图表引用，结果更完整。'
                      : '仅执行命中的业务规则，审查更快且消耗更少。'}
                  </Text>
                </div>
                <Tooltip title="关闭后将跳过未命中业务规则章节的基础文字质量审查。">
                  <Switch checked={qualityCheckEnabled} onChange={setQualityCheckEnabled} />
                </Tooltip>
              </div>
            </Form>
          </div>
        )}
      </Modal>
    </div>
  );
}

export default DashboardPage;
