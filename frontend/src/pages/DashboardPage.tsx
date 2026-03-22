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
  Typography,
} from 'antd';
import {
  FileTextOutlined,
  CheckCircleOutlined,
  SyncOutlined,
  CloseCircleOutlined,
  PlusOutlined,
  ThunderboltOutlined,
} from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import { getReviewList, getReviewStats, ReviewTask } from '../api/reviews';
import { STATUS_LABELS, STATUS_COLORS, PAGE_SIZE } from '../utils/constants';

const { Title } = Typography;

function DashboardPage() {
  const navigate = useNavigate();
  const [tasks, setTasks] = useState<ReviewTask[]>([]);
  const [loading, setLoading] = useState(false);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [statusFilter, setStatusFilter] = useState<string | undefined>();
  const [stats, setStats] = useState({
    total: 0,
    completed: 0,
    processing: 0,
    failed: 0,
    todayCount: 0,
  });

  const fetchTasks = async () => {
    setLoading(true);
    try {
      const res = await getReviewList({ page, pageSize: PAGE_SIZE, status: statusFilter });
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
      const res = await getReviewStats();
      setStats(res.data.data);
    } catch {
      // handled
    }
  };

  useEffect(() => {
    fetchStats();
  }, []);

  useEffect(() => {
    fetchTasks();
  }, [page, statusFilter]);

  const columns: ColumnsType<ReviewTask> = [
    {
      title: '文件名',
      dataIndex: 'fileName',
      key: 'fileName',
      ellipsis: true,
      width: 200,
    },
    {
      title: '审查场景',
      dataIndex: 'scenarioName',
      key: 'scenarioName',
      width: 150,
    },
    {
      title: 'AI 模型',
      dataIndex: 'modelName',
      key: 'modelName',
      width: 120,
    },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      width: 100,
      render: (status: string) => (
        <Tag color={STATUS_COLORS[status]}>{STATUS_LABELS[status]}</Tag>
      ),
    },
    {
      title: '发现问题',
      key: 'findingCount',
      width: 100,
      render: (_, record) => record.findings?.length ?? '-',
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
      width: 100,
      render: (_, record) => (
        <Button
          type="link"
          size="small"
          onClick={() => navigate(`/review/${record.taskId}`)}
          disabled={record.status === 'pending'}
        >
          查看详情
        </Button>
      ),
    },
  ];

  return (
    <div>
      <div className="page-header">
        <Title level={4} style={{ margin: 0 }}>工作台</Title>
        <Button
          type="primary"
          icon={<PlusOutlined />}
          onClick={() => navigate('/review')}
        >
          新建审查
        </Button>
      </div>

      <Row gutter={16} style={{ marginBottom: 24 }}>
        <Col xs={12} sm={6}>
          <Card className="stat-card">
            <Statistic
              title="审查总数"
              value={stats.total}
              prefix={<FileTextOutlined style={{ color: '#1677ff' }} />}
            />
          </Card>
        </Col>
        <Col xs={12} sm={6}>
          <Card className="stat-card">
            <Statistic
              title="已完成"
              value={stats.completed}
              prefix={<CheckCircleOutlined style={{ color: '#52c41a' }} />}
            />
          </Card>
        </Col>
        <Col xs={12} sm={6}>
          <Card className="stat-card">
            <Statistic
              title="进行中"
              value={stats.processing}
              prefix={<SyncOutlined spin style={{ color: '#1677ff' }} />}
            />
          </Card>
        </Col>
        <Col xs={12} sm={6}>
          <Card className="stat-card">
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
        <Col xs={24} sm={12}>
          <Card
            hoverable
            onClick={() => navigate('/rules')}
            style={{ textAlign: 'center', cursor: 'pointer' }}
          >
            <FileTextOutlined style={{ fontSize: 32, color: '#1677ff', marginBottom: 8 }} />
            <div style={{ fontWeight: 500 }}>管理审查规则</div>
            <div style={{ color: '#8c8c8c', fontSize: 13 }}>上传和管理审查规则文件</div>
          </Card>
        </Col>
        <Col xs={24} sm={12}>
          <Card
            hoverable
            onClick={() => navigate('/scenarios')}
            style={{ textAlign: 'center', cursor: 'pointer' }}
          >
            <CloseCircleOutlined style={{ fontSize: 32, color: '#722ed1', marginBottom: 8 }} />
            <div style={{ fontWeight: 500 }}>管理审查场景</div>
            <div style={{ color: '#8c8c8c', fontSize: 13 }}>创建和配置审查场景</div>
          </Card>
        </Col>
      </Row>
    </div>
  );
}

export default DashboardPage;
