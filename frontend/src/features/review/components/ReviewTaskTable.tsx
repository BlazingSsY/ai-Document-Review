import { useNavigate } from 'react-router-dom';
import { Table, Tag, Button, Space, Progress, Popconfirm, message } from 'antd';
import { StopOutlined, RedoOutlined, DeleteOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import type { TablePaginationConfig } from 'antd/es/table';
import type { ReviewTask } from '../api/reviews';
import { cancelReview, reReview, deleteReview } from '../api/reviews';
import { PIPELINE_LABEL } from '../api/pipelineApi';
import { reviewCategoryLabel } from '../registry/reviewFeatures';
import { STATUS_LABELS, STATUS_COLORS } from '../../../shared/utils/constants';

/**
 * 一条任务的问题数。优先用后端缓存的标量，避免列表接口回传完整 aiResult；
 * 历史任务没有该字段时再从明细里数。
 */
export function countReviewProblems(task: ReviewTask): number | '-' {
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

interface ReviewTaskTableProps {
  tasks: ReviewTask[];
  loading: boolean;
  /** taskId → 0~100 的实时进度，来自 useTaskProgress。 */
  progress: Record<string, number>;
  /**
   * 精简模式：工作台概览用。只保留识别任务与进入详情所需的列，去掉写操作——
   * 概览是「看一眼」的地方，取消/重审/删除统一在审查任务中心执行。
   */
  compact?: boolean;
  /** 取消 / 重新审查 / 删除成功后回调，用于刷新列表与统计。 */
  onChanged?: () => void;
  pagination?: TablePaginationConfig | false;
}

function ReviewTaskTable({
  tasks, loading, progress, compact = false, onChanged, pagination = false,
}: ReviewTaskTableProps) {
  const navigate = useNavigate();

  const runAction = async (
    action: (taskId: string) => Promise<unknown>,
    task: ReviewTask,
    successText: string,
  ) => {
    try {
      await action(task.id);
      message.success(successText);
      onChanged?.();
    } catch { /* 统一由请求拦截器提示 */ }
  };

  const statusColumn = {
    title: '状态',
    key: 'status',
    width: 160,
    render: (_: unknown, task: ReviewTask) => {
      const status = task.status?.toLowerCase();
      const tag = <Tag color={STATUS_COLORS[status]}>{STATUS_LABELS[status] || task.status}</Tag>;
      const percent = progress[task.id];
      if (status !== 'processing' || percent === undefined) return tag;
      return (
        <Space direction="vertical" size={2} align="start">
          {tag}
          {/* 固定 110px：状态标签只有五六十像素宽，进度条铺满 160px 的列会显得头重脚轻。
              110px 含 showInfo 的百分比文字，条体约 75px，与标签宽度视觉上对齐。 */}
          <Progress
            percent={percent}
            size="small"
            status="active"
            showInfo
            style={{ width: 110, marginBottom: 0 }}
            strokeColor={{ '0%': '#108ee9', '100%': '#87d068' }}
          />
        </Space>
      );
    },
  };

  const columns: ColumnsType<ReviewTask> = [
    { title: '文件名', dataIndex: 'fileName', key: 'fileName', ellipsis: true, width: 200 },
    ...(compact ? [] : [{
      // 类别与方式合成一列：类别是业务域（审的是什么），方式是管线（怎么审）——
      // 方式从属于类别，拆成两列既占宽度又让人误以为是并列维度。
      title: '审查类型',
      key: 'reviewType',
      width: 170,
      render: (_: unknown, task: ReviewTask) => (
        <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'flex-start', gap: 3 }}>
          <Tag color="purple" style={{ marginInlineEnd: 0 }}>
            {reviewCategoryLabel(task.reviewCategory)}
          </Tag>
          {/* 审查方式是从属信息，用次要灰而不是管线主题色：一列里两个高饱和色块
              会互相抢视线，反而看不出主次。没有 reviewMode 的历史任务按 CHUNK 显示。 */}
          <span style={{ fontSize: 12, color: '#8c8c8c', paddingLeft: 2 }}>
            {PIPELINE_LABEL[task.reviewMode ?? 'CHUNK']}
          </span>
        </div>
      ),
    }, {
      title: 'AI 模型', dataIndex: 'selectedModel', key: 'selectedModel', width: 150,
    }]),
    statusColumn,
    {
      title: '发现问题',
      key: 'issueCount',
      width: 100,
      render: (_: unknown, task: ReviewTask) => countReviewProblems(task),
    },
    {
      title: '创建时间',
      dataIndex: 'createdAt',
      key: 'createdAt',
      width: 180,
      render: (text: string) => (text ? new Date(text).toLocaleString('zh-CN') : '-'),
    },
    {
      title: '操作',
      key: 'action',
      width: compact ? 100 : 280,
      render: (_: unknown, task: ReviewTask) => {
        const status = task.status?.toUpperCase();
        return (
          <Space>
            <Button
              type="link"
              size="small"
              onClick={() => navigate(`/review/${task.id}`)}
              disabled={status === 'PENDING'}
            >
              查看详情
            </Button>
            {!compact && (status === 'COMPLETED' || status === 'FAILED' || status === 'CANCELLED') && (
              <Popconfirm
                title="确定要重新审查此任务吗？"
                onConfirm={() => runAction(reReview, task, '重新审查任务已提交')}
                okText="确定"
                cancelText="取消"
              >
                <Button type="link" size="small" icon={<RedoOutlined />}>重新审查</Button>
              </Popconfirm>
            )}
            {!compact && (status === 'PENDING' || status === 'PROCESSING') && (
              <Popconfirm
                title="确定要取消此审查任务吗？"
                onConfirm={() => runAction(cancelReview, task, '任务已取消')}
                okText="确定"
                cancelText="取消"
              >
                <Button type="link" size="small" danger icon={<StopOutlined />}>取消</Button>
              </Popconfirm>
            )}
            {!compact && status !== 'PROCESSING' && (
              <Popconfirm
                title="确定要删除此任务吗？删除后不可恢复。"
                onConfirm={() => runAction(deleteReview, task, '任务已删除')}
                okText="确定"
                cancelText="取消"
              >
                <Button type="link" size="small" danger icon={<DeleteOutlined />}>删除</Button>
              </Popconfirm>
            )}
          </Space>
        );
      },
    },
  ];

  return (
    <Table
      columns={columns}
      dataSource={tasks}
      rowKey="id"
      loading={loading}
      pagination={pagination}
      size={compact ? 'small' : 'middle'}
    />
  );
}

export default ReviewTaskTable;
