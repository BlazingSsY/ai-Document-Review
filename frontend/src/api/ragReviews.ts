import request, { ApiResponse } from './request';
import type { PaginatedResult } from './rules';
import type { ReviewTask, ReviewListParams, ManualCheckDecisionParams } from './reviews';

// Types are shape-identical to chunk side — ReviewTask carries a reviewMode field.
export type { ReviewTask, ReviewListParams, ManualCheckDecisionParams } from './reviews';

const BASE = '/rag/reviews';

export function submitReview(formData: FormData) {
  return request.post<ApiResponse<ReviewTask>>(`${BASE}/execute`, formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
    timeout: 120000,
  });
}

export function getReviewList(params: ReviewListParams) {
  const { pageSize, ...rest } = params;
  return request.get<ApiResponse<PaginatedResult<ReviewTask>>>(`${BASE}/tasks`, {
    params: { ...rest, size: pageSize },
  });
}

export function getReviewDetail(taskId: string) {
  return request.get<ApiResponse<ReviewTask>>(`${BASE}/tasks/${taskId}`);
}

export function cancelReview(taskId: string) {
  return request.post<ApiResponse<null>>(`${BASE}/tasks/${taskId}/cancel`);
}

export function reReview(taskId: string) {
  return request.post<ApiResponse<ReviewTask>>(`${BASE}/tasks/${taskId}/re-review`);
}

export function retryFailedChunks(taskId: string) {
  return request.post<ApiResponse<ReviewTask>>(`${BASE}/tasks/${taskId}/retry-failed-chunks`);
}

export function deleteReview(taskId: string) {
  return request.delete<ApiResponse<null>>(`${BASE}/tasks/${taskId}`);
}

export function exportReviewExcel(taskId: string) {
  return request.get(`${BASE}/tasks/${taskId}/export`, { responseType: 'blob' });
}

export function updateCheckDecision(taskId: string, params: ManualCheckDecisionParams) {
  return request.put<ApiResponse<ReviewTask>>(`${BASE}/tasks/${taskId}/check-decisions`, params);
}

export function exportReviewAudit(taskId: string) {
  return request.get(`${BASE}/tasks/${taskId}/audit/export`, { responseType: 'blob' });
}

export function exportReviewReport(taskId: string) {
  return request.get(`${BASE}/tasks/${taskId}/report`, { responseType: 'blob' });
}

export function getReviewStats() {
  return request.get<ApiResponse<{
    total: number;
    completed: number;
    processing: number;
    failed: number;
    todayCount: number;
  }>>(`${BASE}/stats`);
}
