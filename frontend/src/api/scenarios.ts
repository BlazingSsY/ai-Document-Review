import request, { ApiResponse } from './request';
import { PaginatedResult } from './rules';

export interface Scenario {
  id: number;
  name: string;
  description: string;
  creatorId: number;
  ruleIds: number[];
}

export interface CreateScenarioParams {
  name: string;
  description: string;
  ruleIds: number[];
}

export interface ScenarioListParams {
  page: number;
  pageSize: number;
  keyword?: string;
}

export function getScenarioList(params: ScenarioListParams) {
  const { pageSize, ...rest } = params;
  return request.get<ApiResponse<PaginatedResult<Scenario>>>('/scenarios', {
    params: { ...rest, size: pageSize },
  });
}

export function getScenarioDetail(id: number) {
  return request.get<ApiResponse<Scenario>>(`/scenarios/${id}`);
}

export function createScenario(params: CreateScenarioParams) {
  return request.post<ApiResponse<Scenario>>('/scenarios', params);
}

export function updateScenario(id: number, params: CreateScenarioParams) {
  return request.put<ApiResponse<Scenario>>(`/scenarios/${id}`, params);
}

export function deleteScenario(id: number) {
  return request.delete<ApiResponse<null>>(`/scenarios/${id}`);
}
