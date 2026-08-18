/**
 * 跨管线的工作台接口，以及管线的展示元数据。
 *
 * 后端保留 CHUNK / SAR 两条管线，但前端目前只开放 CHUNK：新建、列表、统计一律传
 * mode=CHUNK，因此这里不再维护「按管线挑 API 客户端」的派发层——各页面直接 import
 * 各自需要的 chunk 客户端。只有任务详情里回显的管线名称仍需要 SAR 的文案，故保留
 * PIPELINE_LABEL / PIPELINE_COLOR 两张表。
 */

import request, { ApiResponse } from '../../../shared/api/request';
import type { PaginatedResult } from '../../rules/api/rules';
import type { ReviewMode, ReviewTask } from './reviews';

export type { ReviewMode } from './reviews';

/** 显示给用户看的管线名称。 */
export const PIPELINE_LABEL: Record<ReviewMode, string> = {
  CHUNK: '全文逐章审查',
  SAR: '结构化审查',
};

/** UI 上为每条管线分配一个稳定的色彩，前端的 Tag / 列表色条共享。 */
export const PIPELINE_COLOR: Record<ReviewMode, string> = {
  CHUNK: 'blue',
  SAR: 'green',
};

// ---- Unified workbench endpoints ----

export interface UnifiedListParams {
  page: number;
  pageSize: number;
  mode?: ReviewMode | 'ALL';
  status?: string;
  /** 审查类别（业务域）。省略表示不限类别，用于跨业务域的汇总视图。 */
  category?: string;
}

export interface UnifiedStats {
  total: number;
  completed: number;
  processing: number;
  failed: number;
  todayCount: number;
  byMode: Record<ReviewMode, {
    total: number;
    completed: number;
    processing: number;
    failed: number;
    todayCount: number;
  }>;
}

export function getUnifiedReviewList(params: UnifiedListParams) {
  const { pageSize, ...rest } = params;
  return request.get<ApiResponse<PaginatedResult<ReviewTask>>>('/reviews/all', {
    params: { ...rest, size: pageSize },
  });
}

export function getUnifiedReviewStats(category?: string) {
  return request.get<ApiResponse<UnifiedStats>>('/reviews/stats/all', {
    params: category ? { category } : undefined,
  });
}

/**
 * Look up a task by id without knowing its pipeline up-front. Hits the backend's
 * {@code /reviews/by-id/{taskId}} endpoint which checks both tables internally
 * and returns the row with {@code reviewMode} populated.
 */
export function getReviewDetailAnyPipeline(taskId: string) {
  // light=true 让后端剥离 originalSources / chunkResults 两个大字段，首屏只拿矩阵 +
  // 概要，秒出。原文/溯源由 getReviewSourcesAnyPipeline 在渲染后后台补齐。
  return request.get<ApiResponse<ReviewTask>>(`/reviews/by-id/${taskId}`, {
    params: { light: true },
  });
}

/** 详情页按需拉取的「溯源原文」负载，对应后端 /reviews/by-id/{taskId}/sources。 */
export interface ReviewSources {
  originalSources?: Array<Record<string, unknown>>;
  chunkResults?: Array<Record<string, unknown>>;
}

export function getReviewSourcesAnyPipeline(taskId: string) {
  return request.get<ApiResponse<ReviewSources>>(`/reviews/by-id/${taskId}/sources`);
}
