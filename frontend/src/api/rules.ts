import request, { ApiResponse } from './request';

export interface Rule {
  id: number;
  name: string;
  fileName: string;
  content: string;
  prompt: string;
  createdAt: string;
  updatedAt: string;
}

export interface RuleListParams {
  page: number;
  pageSize: number;
  keyword?: string;
}

export interface PaginatedResult<T> {
  records: T[];
  total: number;
  page: number;
  size: number;
  totalPages: number;
}

export function getRuleList(params: RuleListParams) {
  return request.get<ApiResponse<PaginatedResult<Rule>>>('/rules', { params });
}

export function getRuleDetail(id: number) {
  return request.get<ApiResponse<Rule>>(`/rules/${id}`);
}

export function uploadRule(formData: FormData) {
  return request.post<ApiResponse<Rule>>('/rules/upload', formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
  });
}

export function deleteRule(id: number) {
  return request.delete<ApiResponse<null>>(`/rules/${id}`);
}
