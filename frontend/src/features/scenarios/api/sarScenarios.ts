import request, { ApiResponse } from '../../../shared/api/request';
import { PaginatedResult } from '../../rules/api/rules';
import type {
  Scenario, CreateScenarioParams, ScenarioListParams,
} from './scenarios';

// Types are shape-identical to chunk side — re-export so callers can import from
// either file without confusion.
export type { Scenario, CreateScenarioParams, ScenarioListParams } from './scenarios';

const BASE = '/sar/scenarios';

export function getScenarioList(params: ScenarioListParams) {
  const { pageSize, ...rest } = params;
  return request.get<ApiResponse<PaginatedResult<Scenario>>>(BASE, {
    params: { ...rest, size: pageSize },
  });
}

export function getScenarioDetail(id: number) {
  return request.get<ApiResponse<Scenario>>(`${BASE}/${id}`);
}

export function createScenario(params: CreateScenarioParams) {
  return request.post<ApiResponse<Scenario>>(BASE, params);
}

export function updateScenario(id: number, params: CreateScenarioParams) {
  return request.put<ApiResponse<Scenario>>(`${BASE}/${id}`, params);
}

export function deleteScenario(id: number) {
  return request.delete<ApiResponse<null>>(`${BASE}/${id}`);
}
