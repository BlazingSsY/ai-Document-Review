import { useState } from 'react';
import { Navigate, useParams } from 'react-router-dom';
import { Card, Button, Space, Select, Typography, Tag, Result } from 'antd';
import { PlusOutlined, LockOutlined } from '@ant-design/icons';
import CreateReviewModal from '../components/CreateReviewModal';
import ReviewTaskTable from '../components/ReviewTaskTable';
import { useReviewTasks } from '../hooks/useReviewTasks';
import { PIPELINE_LABEL } from '../api/pipelineApi';
import { findReviewFeatureBySlug } from '../registry/reviewFeatures';
import { PAGE_SIZE } from '../../../shared/utils/constants';
import { hasFeature } from '../../../shared/utils/permissions';
import useAuthStore from '../../auth/store/authStore';

const { Title, Text } = Typography;

const STATUS_OPTIONS = [
  { label: '待处理', value: 'pending' },
  { label: '处理中', value: 'processing' },
  { label: '已完成', value: 'completed' },
  { label: '失败', value: 'failed' },
  { label: '已取消', value: 'cancelled' },
];

/**
 * 一个审查业务域的操作台：发起审查、跟踪进度、管理任务。
 *
 * 页面本身与具体业务域无关——业务域由路由片段 `/reviews/:slug` 解析，文案、类别、管线
 * 都从注册表读取。新增审查功能只需在 REVIEW_FEATURES 加一条，本页无需改动，且各业务域
 * 的任务列表与统计按 category 在后端隔离，互不影响。
 */
function ReviewTaskCenterPage() {
  const { slug } = useParams<{ slug: string }>();
  const user = useAuthStore((state) => state.user);
  const feature = findReviewFeatureBySlug(slug);
  const [createOpen, setCreateOpen] = useState(false);

  const authorized = Boolean(feature) && hasFeature(user, feature!.permissionCode);
  const {
    tasks, loading, total, page, setPage, statusFilter, setStatusFilter,
    stats, progress, refresh,
  } = useReviewTasks({
    feature: feature ?? null,
    enabled: authorized,
    pageSize: PAGE_SIZE,
  });

  // 未登记的业务域（手敲 URL 或功能已下线）回到工作台，而不是渲染一个空壳。
  if (!feature) return <Navigate to="/dashboard" replace />;

  if (!authorized) {
    return (
      <Card>
        <Result
          icon={<LockOutlined style={{ color: '#8c8c8c' }} />}
          title="当前未分配该审查功能"
          subTitle={`请联系本单位管理员或平台管理员，为您分配“${feature.label}”功能及所需规则库。`}
        />
      </Card>
    );
  }

  return (
    <div>
      <div className="page-header">
        <Space size={12} align="center">
          <Title level={4} style={{ margin: 0 }}>{feature.label}</Title>
          <Tag color="purple">{PIPELINE_LABEL[feature.reviewMode]}</Tag>
        </Space>
        <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateOpen(true)}>
          新建审查
        </Button>
      </div>

      <Card>
        <div style={{
          marginBottom: 16,
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center',
          gap: 16,
        }}>
          <Space size={4} direction="vertical">
            <Title level={5} style={{ margin: 0 }}>审查任务列表</Title>
            <Text type="secondary" style={{ fontSize: 12 }}>
              本类别共 {stats.total} 个任务 · 进行中 {stats.processing} · 已完成 {stats.completed}
            </Text>
          </Space>
          <Space>
            <Select
              placeholder="状态筛选"
              allowClear
              style={{ width: 140 }}
              value={statusFilter}
              onChange={(value) => { setStatusFilter(value); setPage(1); }}
              options={STATUS_OPTIONS}
            />
            <Button onClick={() => { void refresh(); }}>刷新</Button>
          </Space>
        </div>

        <ReviewTaskTable
          tasks={tasks}
          loading={loading}
          progress={progress}
          onChanged={() => { void refresh(); }}
          pagination={{
            current: page,
            pageSize: PAGE_SIZE,
            total,
            showTotal: (count) => `共 ${count} 条`,
            onChange: setPage,
            showSizeChanger: false,
          }}
        />
      </Card>

      <CreateReviewModal
        feature={feature}
        open={createOpen}
        onCancel={() => setCreateOpen(false)}
        onSubmitted={() => { void refresh(); }}
      />
    </div>
  );
}

export default ReviewTaskCenterPage;
