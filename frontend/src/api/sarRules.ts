import request, { ApiResponse } from './request';
import type {
  Rule, RuleLibrary, RuleListParams, RuleMetadataUpdate, ChecklistImportResult,
  PaginatedResult,
} from './rules';

// Types are shape-identical to chunk side.
export type {
  Rule, RuleLibrary, RuleListParams, RuleMetadataUpdate, ChecklistImportResult,
  PaginatedResult, RuleCheck,
} from './rules';

const RULES_BASE = '/sar/rules';
const LIBRARIES_BASE = '/sar/rule-libraries';

export function getRuleList(params: RuleListParams) {
  const { pageSize, ...rest } = params;
  return request.get<ApiResponse<PaginatedResult<Rule>>>(RULES_BASE, {
    params: { ...rest, size: pageSize },
  });
}

export function getRuleDetail(id: number) {
  return request.get<ApiResponse<Rule>>(`${RULES_BASE}/${id}`);
}

export function uploadRule(formData: FormData) {
  return request.post<ApiResponse<Rule[]>>(`${RULES_BASE}/upload`, formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
  });
}

export function importChecklist(formData: FormData) {
  return request.post<ApiResponse<ChecklistImportResult>>(`${RULES_BASE}/import-checklist`, formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
  });
}

export function updateRuleMetadata(id: number, payload: RuleMetadataUpdate) {
  return request.put<ApiResponse<Rule>>(`${RULES_BASE}/${id}/metadata`, payload);
}

export function deleteRule(id: number) {
  return request.delete<ApiResponse<null>>(`${RULES_BASE}/${id}`);
}

export function getRuleLibraryList(params: { page: number; pageSize: number }) {
  const { pageSize, ...rest } = params;
  return request.get<ApiResponse<PaginatedResult<RuleLibrary>>>(LIBRARIES_BASE, {
    params: { ...rest, size: pageSize },
  });
}

export function getAllRuleLibraries() {
  return request.get<ApiResponse<RuleLibrary[]>>(`${LIBRARIES_BASE}/all`);
}

export function createRuleLibrary(params: { name: string; description?: string }) {
  return request.post<ApiResponse<RuleLibrary>>(LIBRARIES_BASE, params);
}

export function updateRuleLibrary(id: number, params: { name: string; description?: string }) {
  return request.put<ApiResponse<RuleLibrary>>(`${LIBRARIES_BASE}/${id}`, params);
}

export function deleteRuleLibrary(id: number) {
  return request.delete<ApiResponse<null>>(`${LIBRARIES_BASE}/${id}`);
}
