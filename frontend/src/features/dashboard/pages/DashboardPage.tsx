import { useNavigate } from 'react-router-dom';
import { Card, Row, Col, Statistic, Typography, Button, Result, Empty } from 'antd';
import {
  FileTextOutlined,
  CheckCircleOutlined,
  SyncOutlined,
  ThunderboltOutlined,
  AppstoreOutlined,
  ProfileOutlined,
  ArrowRightOutlined,
  LockOutlined,
} from '@ant-design/icons';
import type { ReactNode } from 'react';
import ReviewTaskTable from '../../review/components/ReviewTaskTable';
import { useReviewTasks } from '../../review/hooks/useReviewTasks';
import { reviewFeaturePath } from '../../review/registry/reviewFeatures';
import { useAuthorizedReviewFeatures } from '../../../shared/utils/permissions';
import '../styles/dashboard.css';

const { Title } = Typography;

/** 概览只列最近这么多条；完整列表在各业务域的审查任务中心。 */
const RECENT_TASK_COUNT = 5;

interface QuickEntry {
  path: string;
  icon: ReactNode;
  title: string;
  description: string;
}

/** 复用共享规则库的业务域共同使用的配置入口。 */
const SHARED_CONFIG_ENTRIES: QuickEntry[] = [
  {
    path: '/chunk/scenarios',
    icon: <AppstoreOutlined />,
    title: '审查场景',
    description: '把若干规则库组合成一次审查要用的场景',
  },
  {
    path: '/chunk/rules',
    icon: <ProfileOutlined />,
    title: '审查规则',
    description: '维护规则库、规则文件与检查项内容',
  },
];

/**
 * 工作台 = 跨业务域概览。数字卡看整体水位，最近任务看有没有异常，快捷入口去干活。
 * 新建审查、筛选、取消/重审/删除等操作都在各业务域自己的审查任务中心。
 *
 * 这里刻意不传 feature：概览要的就是所有已授权业务域的合计视图，新增审查功能后自动
 * 纳入统计与快捷入口，无需改动本页。
 */
function DashboardPage() {
  const navigate = useNavigate();
  const reviewFeatures = useAuthorizedReviewFeatures();
  const { tasks, loading, stats, progress } = useReviewTasks({
    feature: null,
    enabled: reviewFeatures.length > 0,
    pageSize: RECENT_TASK_COUNT,
    paginated: false,
  });

  if (reviewFeatures.length === 0) {
    return (
      <Card>
        <Result
          icon={<LockOutlined style={{ color: '#8c8c8c' }} />}
          title="当前未分配审查功能"
          subTitle="请联系本单位管理员或平台管理员，为您分配所需的审查功能及规则库。"
        />
      </Card>
    );
  }

  const quickEntries: QuickEntry[] = [
    ...reviewFeatures.map((feature) => ({
      path: reviewFeaturePath(feature),
      icon: feature.icon,
      title: feature.label,
      description: feature.description,
    })),
    ...(reviewFeatures.some((feature) => feature.usesSharedRuleLibraries)
      ? SHARED_CONFIG_ENTRIES : []),
  ];

  return (
    <div>
      <div className="page-header">
        <Title level={4} style={{ margin: 0 }}>工作台</Title>
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
              prefix={<SyncOutlined spin={stats.processing > 0} style={{ color: '#722ed1' }} />}
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

      <Card style={{ marginBottom: 16 }}>
        <div style={{
          marginBottom: 16,
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center',
        }}>
          <Title level={5} style={{ margin: 0 }}>最近审查</Title>
          <Button type="link" onClick={() => navigate(reviewFeaturePath(reviewFeatures[0]))}>
            查看全部 <ArrowRightOutlined />
          </Button>
        </div>
        {!loading && tasks.length === 0 ? (
          <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="还没有审查任务">
            <Button type="primary" onClick={() => navigate(reviewFeaturePath(reviewFeatures[0]))}>
              去发起一次审查
            </Button>
          </Empty>
        ) : (
          <ReviewTaskTable tasks={tasks} loading={loading} progress={progress} compact />
        )}
      </Card>

      <Row gutter={[16, 16]}>
        {quickEntries.map((entry) => (
          <Col xs={24} lg={8} key={entry.path}>
            <Card
              className="review-library-entry"
              hoverable
              style={{ height: '100%' }}
              onClick={() => navigate(entry.path)}
            >
              <div className="review-library-entry__icon">{entry.icon}</div>
              <div style={{ minWidth: 0 }}>
                <div className="review-library-entry__title">{entry.title}</div>
                <div className="review-library-entry__description">{entry.description}</div>
              </div>
              <ArrowRightOutlined className="review-library-entry__arrow" />
            </Card>
          </Col>
        ))}
      </Row>
    </div>
  );
}

export default DashboardPage;
