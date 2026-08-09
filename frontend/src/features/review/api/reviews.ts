import request, { ApiResponse } from '../../../shared/api/request';
import { PaginatedResult } from '../../rules/api/rules';

export type ReviewMode = 'CHUNK' | 'SAR';

export interface ReviewTask {
  id: string;
  userId: number;
  fileName: string;
  scenarioId: number;
  selectedModel: string;
  status: string;
  aiResult: Record<string, unknown> | null;
  createdAt: string;
  updatedAt: string;
  failReason?: string;
  /** Cached scalar problem count for the list (backend avoids shipping full aiResult). */
  problemCount?: number | null;
  /** 进行中任务的最近进度（0~100），来自后端内存进度表；硬刷新后用它立即显示进度条。 */
  progress?: number | null;
  /** 任务所属管线。后端在 DTO 序列化时填入；前端按此值分流后续 API 调用。 */
  reviewMode?: ReviewMode;
  /**
   * 任务所属审查类别（业务域），如 'ENV_TEST_OUTLINE'。与 reviewMode 正交：
   * 类别说明审的是什么文件，reviewMode 说明用什么方法审。取值见 REVIEW_CATEGORIES。
   */
  reviewCategory?: string;
}

export interface ReviewListParams {
  page: number;
  pageSize: number;
  status?: string;
}

export function submitReview(formData: FormData) {
  return request.post<ApiResponse<ReviewTask>>('/reviews/execute', formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
    // Uploads carry the document body, so the default 30s timeout is too aggressive
    // for large files on slow networks. The handler returns as soon as the task
    // record is inserted and async review is dispatched, so this is just an upper
    // bound on the upload itself.
    timeout: 120000,
  });
}

export function getReviewList(params: ReviewListParams) {
  const { pageSize, ...rest } = params;
  return request.get<ApiResponse<PaginatedResult<ReviewTask>>>('/reviews/tasks', {
    params: { ...rest, size: pageSize },
  });
}

export function getReviewDetail(taskId: string) {
  return request.get<ApiResponse<ReviewTask>>(`/reviews/tasks/${taskId}`);
}

export function cancelReview(taskId: string) {
  return request.post<ApiResponse<null>>(`/reviews/tasks/${taskId}/cancel`);
}

export function reReview(taskId: string) {
  return request.post<ApiResponse<ReviewTask>>(`/reviews/tasks/${taskId}/re-review`);
}

export function retryFailedChunks(taskId: string) {
  return request.post<ApiResponse<ReviewTask>>(`/reviews/tasks/${taskId}/retry-failed-chunks`);
}

export function deleteReview(taskId: string) {
  return request.delete<ApiResponse<null>>(`/reviews/tasks/${taskId}`);
}

export function exportReviewExcel(taskId: string) {
  return request.get(`/reviews/tasks/${taskId}/export`, {
    responseType: 'blob',
  });
}

export interface ManualCheckDecisionParams {
  checkCode: string;
  /** RAG 一检查项可展开多条违规，传 finding_id 精确定位；chunk 侧可省略。 */
  findingId?: string;
  sourceChunk?: number;
  finalStatus: string;
  accepted?: boolean;
  comment?: string;
}

export function updateCheckDecision(taskId: string, params: ManualCheckDecisionParams) {
  return request.put<ApiResponse<ReviewTask>>(`/reviews/tasks/${taskId}/check-decisions`, params);
}

export function exportReviewAudit(taskId: string) {
  return request.get(`/reviews/tasks/${taskId}/audit/export`, {
    responseType: 'blob',
  });
}

export function exportReviewReport(taskId: string) {
  return request.get(`/reviews/tasks/${taskId}/report`, {
    responseType: 'blob',
  });
}

/**
 * 一次「原文定位」编辑。只送 nodeId，不送文档内位置：后端导出时重新解析原始文档来
 * 定位段落，浏览器端拿到的旧数据因此无法写错段落。
 */
export interface SourceEditParams {
  nodeId: string;
  sourceId?: string;
  nodeType?: string;
  originalText: string;
  newText: string;
  /** 表格单元格 1 基坐标；普通段落不传。 */
  cellRow?: number;
  cellColumn?: number;
}

export function saveSourceEdit(taskId: string, params: SourceEditParams) {
  return request.put<ApiResponse<ReviewTask>>(`/reviews/tasks/${taskId}/source-edits`, params);
}

export function clearSourceEdits(taskId: string) {
  return request.delete<ApiResponse<ReviewTask>>(`/reviews/tasks/${taskId}/source-edits`);
}

/** 原始文档 + 已保存的修改，导出为格式一致的 .docx。 */
export function exportRevisedDocument(taskId: string) {
  return request.get(`/reviews/tasks/${taskId}/revised-document`, {
    responseType: 'blob',
  });
}

export function getReviewStats() {
  return request.get<ApiResponse<{
    total: number;
    completed: number;
    processing: number;
    failed: number;
    todayCount: number;
  }>>('/reviews/stats');
}
