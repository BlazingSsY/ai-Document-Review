import request, { ApiResponse } from './request';
import { PaginatedResult } from './rules';

export interface AIModel {
  id: number;
  name: string;
  provider: string;
  modelKey: string;
  apiEndpoint: string;
  apiKey: string;
  maxTokens: number;
  temperature: number;
  enabled: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface CreateModelParams {
  name: string;
  provider: string;
  modelKey: string;
  apiEndpoint: string;
  apiKey: string;
  maxTokens: number;
  temperature: number;
  enabled: boolean;
}

export interface ModelListParams {
  page: number;
  pageSize: number;
}

export function getModelList(params: ModelListParams) {
  const { pageSize, ...rest } = params;
  return request.get<ApiResponse<PaginatedResult<AIModel>>>('/models', {
    params: { ...rest, size: pageSize },
  });
}

export function getEnabledModels() {
  return request.get<ApiResponse<AIModel[]>>('/models/enabled');
}

export function getModelDetail(id: number) {
  return request.get<ApiResponse<AIModel>>(`/models/${id}`);
}

export function createModel(params: CreateModelParams) {
  return request.post<ApiResponse<AIModel>>('/models', params);
}

export function updateModel(id: number, params: Partial<CreateModelParams>) {
  return request.put<ApiResponse<AIModel>>(`/models/${id}`, params);
}

export function deleteModel(id: number) {
  return request.delete<ApiResponse<null>>(`/models/${id}`);
}

export function toggleModel(id: number, enabled: boolean) {
  return request.put<ApiResponse<null>>(`/models/${id}/toggle`, { enabled });
}
