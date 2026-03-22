import request, { ApiResponse } from './request';
import { PaginatedResult } from './rules';

export interface ReviewFinding {
  id: number;
  severity: 'high' | 'medium' | 'low';
  category: string;
  originalText: string;
  suggestion: string;
  explanation: string;
  position?: { start: number; end: number };
  status: 'pending' | 'accepted' | 'rejected';
}

export interface ReviewTask {
  id: number;
  taskId: string;
  fileName: string;
  scenarioId: number;
  scenarioName: string;
  modelId: number;
  modelName: string;
  status: 'pending' | 'processing' | 'completed' | 'failed';
  progress: number;
  findings: ReviewFinding[];
  documentContent: string;
  createdAt: string;
  completedAt?: string;
  errorMessage?: string;
}

export interface SubmitReviewParams {
  scenarioId: number;
  modelId: number;
}

export interface ReviewListParams {
  page: number;
  pageSize: number;
  status?: string;
}

export function submitReview(formData: FormData) {
  return request.post<ApiResponse<ReviewTask>>('/reviews/execute', formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
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

export function getReviewStatus(taskId: string) {
  return request.get<ApiResponse<{ status: string; progress: number }>>(`/reviews/tasks/${taskId}/status`);
}

export function updateFindingStatus(taskId: string, findingId: number, status: 'accepted' | 'rejected') {
  return request.put<ApiResponse<null>>(`/reviews/tasks/${taskId}/findings/${findingId}`, { status });
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
