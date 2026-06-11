import request, { ApiResponse } from './request';
import { PaginatedResult } from './rules';

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

export function getReviewStats() {
  return request.get<ApiResponse<{
    total: number;
    completed: number;
    processing: number;
    failed: number;
    todayCount: number;
  }>>('/reviews/stats');
}
