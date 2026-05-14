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
  /**
   * When true the backend treats this as a thinking/reasoning model: temperature is
   * omitted from the API call (server enforces its own default, e.g. Kimi K2.6
   * locks it to 1.0) and max_tokens is bumped to ≥ 16 000 so the chain-of-thought
   * has room to finish.
   */
  thinkingMode: boolean;
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
  thinkingMode: boolean;
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

export interface TestConnectionResult {
  ok: boolean;
  resolvedUrl: string;
  latencyMs: number;
  reply: string;
}

export interface TestConnectionParams extends Partial<CreateModelParams> {
  id?: number;
}

export function testModelConnection(params: TestConnectionParams) {
  return request.post<ApiResponse<TestConnectionResult>>('/models/test-connection', params);
}

export interface SuggestThinkingModeResult {
  modelKey: string;
  thinkingMode: boolean;
}

/**
 * Ask the backend whether the given modelKey looks like a thinking-mode model.
 * Used by the model-config dialog to pre-tick the switch as the user types.
 */
export function suggestThinkingMode(modelKey: string) {
  return request.get<ApiResponse<SuggestThinkingModeResult>>(
    '/models/suggest-thinking-mode',
    { params: { modelKey } },
  );
}
