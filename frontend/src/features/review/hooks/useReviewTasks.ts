import { useCallback, useEffect, useState } from 'react';
import type { ReviewTask } from '../api/reviews';
import {
  getUnifiedReviewList, getUnifiedReviewStats, type UnifiedStats,
} from '../api/pipelineApi';
import type { ReviewFeatureDef } from '../registry/reviewFeatures';
import { useTaskProgress } from './useTaskProgress';

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

interface UseReviewTasksOptions {
  /**
   * 要看的审查功能。传入具体功能时列表与统计都收敛到该业务域——这是新增审查功能不会
   * 污染既有功能任务列表的关键；传 null 表示跨业务域汇总（工作台概览）。
   */
  feature: ReviewFeatureDef | null;
  /** 无任何审查功能授权时不发请求、不订阅进度。 */
  enabled: boolean;
  pageSize: number;
  /** 概览只读一页且不带筛选，传 false 可省掉分页与状态筛选。 */
  paginated?: boolean;
}

/**
 * 「工作台概览」与各业务域「审查任务中心」共用的任务列表数据源。
 *
 * 页面之间的差异只有作用域、分页与展示密度，取数逻辑（按类别+管线过滤、统计取对应管线、
 * WS 终态后刷新）完全一致，因此收在这里，避免多份会各自漂移的副本。
 */
export function useReviewTasks({
  feature, enabled, pageSize, paginated = true,
}: UseReviewTasksOptions) {
  const [tasks, setTasks] = useState<ReviewTask[]>([]);
  const [loading, setLoading] = useState(false);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [statusFilter, setStatusFilter] = useState<string | undefined>();
  const [stats, setStats] = useState<UnifiedStats>(EMPTY_STATS);

  const category = feature?.category;
  // 概览跨业务域汇总，各功能的管线可能不同，故不限管线。
  const mode = feature?.reviewMode;

  const { progress, seedFromTasks } = useTaskProgress(enabled, () => {
    void refresh();
  });

  const fetchTasks = useCallback(async () => {
    if (!enabled) return;
    setLoading(true);
    try {
      const res = await getUnifiedReviewList({
        page: paginated ? page : 1,
        pageSize,
        mode,
        category,
        status: paginated ? statusFilter : undefined,
      });
      const data = res.data.data;
      const records = data.records || [];
      setTasks(records);
      setTotal(data.total);
      seedFromTasks(records);
    } catch {
      // 统一由请求拦截器提示
    } finally {
      setLoading(false);
    }
    // seedFromTasks 每次渲染都是新引用，纳入依赖会让 fetchTasks 每帧失效；它只读 state
    // setter，本身无外部依赖，故有意排除。
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [enabled, page, pageSize, statusFilter, paginated, mode, category]);

  const fetchStats = useCallback(async () => {
    if (!enabled) return;
    try {
      const res = await getUnifiedReviewStats(category);
      const all = res.data.data;
      // 指定了功能就只看它那条管线的数字；概览不限管线，用跨管线合计。
      setStats(mode ? { ...all, ...all.byMode[mode] } : all);
    } catch {
      // 统一由请求拦截器提示
    }
  }, [enabled, category, mode]);

  const refresh = useCallback(async () => {
    await Promise.all([fetchTasks(), fetchStats()]);
  }, [fetchTasks, fetchStats]);

  // 切换业务域时回到第一页，否则会停在上一个域不存在的页码上显示空列表。
  useEffect(() => { setPage(1); }, [category]);

  useEffect(() => { void fetchTasks(); }, [fetchTasks]);
  useEffect(() => { void fetchStats(); }, [fetchStats]);

  return {
    tasks,
    loading,
    total,
    page,
    setPage,
    statusFilter,
    setStatusFilter,
    stats,
    progress,
    refresh,
  };
}

export default useReviewTasks;
