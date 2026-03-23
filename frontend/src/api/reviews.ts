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

export function getReviewStats() {
  return request.get<ApiResponse<{
    total: number;
    completed: number;
    processing: number;
    failed: number;
    todayCount: number;
  }>>('/reviews/stats');
}
