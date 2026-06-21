/**
 * 按管线（CHUNK / RAG）派发到对应的 API 客户端。
 *
 * 设计：CHUNK 与 RAG 两条管线的接口在结构上是镜像的——同样的字段、同样的方法签名、
 * 只是 URL 前缀不同。让公用页面（ScenarioListPage / RuleListPage / 工作台等）通过
 * 这里拿到一个统一形状的 client，避免把 "if reviewMode === 'RAG' then ... else ..."
 * 散落到 N 个组件里。
 */

import request, { ApiResponse } from './request';
import type { PaginatedResult } from './rules';
import type { ReviewMode, ReviewTask } from './reviews';

import * as chunkScenarios from './scenarios';
import * as ragScenarios from './ragScenarios';
import * as sarScenarios from './sarScenarios';
import * as chunkRules from './rules';
import * as ragRules from './ragRules';
import * as sarRules from './sarRules';
import * as chunkReviews from './reviews';
import * as ragReviews from './ragReviews';
import * as sarReviews from './sarReviews';

export type { ReviewMode } from './reviews';

/** 显示给用户看的管线名称。 */
export const PIPELINE_LABEL: Record<ReviewMode, string> = {
  CHUNK: '全文逐章审查',
  RAG: '智能召回审查',
  SAR: '结构化精准审查',
};

/** UI 上为每条管线分配一个稳定的色彩，前端的 Tag / 列表色条共享。 */
export const PIPELINE_COLOR: Record<ReviewMode, string> = {
  CHUNK: 'blue',
  RAG: 'purple',
  SAR: 'green',
};

export type ScenarioApi = typeof chunkScenarios;
export type RuleApi = typeof chunkRules;
export type ReviewApi = typeof chunkReviews;

export function getScenarioApi(mode: ReviewMode): ScenarioApi {
  if (mode === 'RAG') return ragScenarios as unknown as ScenarioApi;
  if (mode === 'SAR') return sarScenarios as unknown as ScenarioApi;
  return chunkScenarios;
}

export function getRuleApi(mode: ReviewMode): RuleApi {
  if (mode === 'RAG') return ragRules as unknown as RuleApi;
  if (mode === 'SAR') return sarRules as unknown as RuleApi;
  return chunkRules;
}

export function getReviewApi(mode: ReviewMode): ReviewApi {
  if (mode === 'RAG') return ragReviews as unknown as ReviewApi;
  if (mode === 'SAR') return sarReviews as unknown as ReviewApi;
  return chunkReviews;
}

// ---- Unified workbench endpoints ----

export interface UnifiedListParams {
  page: number;
  pageSize: number;
  mode?: ReviewMode | 'ALL';
  status?: string;
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

export function getUnifiedReviewStats() {
  return request.get<ApiResponse<UnifiedStats>>('/reviews/stats/all');
}

/**
 * Look up a task by id without knowing its pipeline up-front. Hits the backend's
 * {@code /reviews/by-id/{taskId}} endpoint which checks both tables internally
 * and returns the row with {@code reviewMode} populated. The workspace page uses
 * this for its initial load — every subsequent call dispatches via
 * {@link getReviewApi}{@code (task.reviewMode)} so we never have to fall back
 * client-side.
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
