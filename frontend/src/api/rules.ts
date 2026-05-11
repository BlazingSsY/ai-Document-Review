import request, { ApiResponse } from './request';

export interface Rule {
  id: number;
  ruleName: string;
  fileType: string;
  content: string;
  creatorId: number;
  libraryId?: number;
  updatedAt: string;
  isValid: boolean;

  // Editable metadata. Auto-filled on upload (frontmatter / DO-160G auto-detection);
  // overridable via PUT /api/v1/rules/{id}/metadata.
  ruleCode?: string;
  ruleType?: string;
  documentType?: string;
  standard?: string;
  sections?: string[];
  keywords?: string[];
  severity?: string;
  description?: string;
  sourceFile?: string;
}

export interface RuleMetadataUpdate {
  ruleName?: string;
  ruleCode?: string;
  ruleType?: string;
  documentType?: string;
  standard?: string;
  sections?: string[];
  keywords?: string[];
  severity?: string;
  description?: string;
}

export interface RuleLibrary {
  id: number;
  name: string;
  description: string;
  ruleCount: number;
  createdAt: string;
  updatedAt: string;
}

export interface RuleListParams {
  page: number;
  pageSize: number;
  libraryId?: number;
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
  const { pageSize, ...rest } = params;
  return request.get<ApiResponse<PaginatedResult<Rule>>>('/rules', {
    params: { ...rest, size: pageSize },
  });
}

export function getRuleDetail(id: number) {
  return request.get<ApiResponse<Rule>>(`/rules/${id}`);
}

export function uploadRule(formData: FormData) {
  // Backend now returns a list (one rule file can expand into multiple rules)
  return request.post<ApiResponse<Rule[]>>('/rules/upload', formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
  });
}

export function updateRuleMetadata(id: number, payload: RuleMetadataUpdate) {
  return request.put<ApiResponse<Rule>>(`/rules/${id}/metadata`, payload);
}

export function deleteRule(id: number) {
  return request.delete<ApiResponse<null>>(`/rules/${id}`);
}

// Rule Library APIs
export function getRuleLibraryList(params: { page: number; pageSize: number }) {
  const { pageSize, ...rest } = params;
  return request.get<ApiResponse<PaginatedResult<RuleLibrary>>>('/rule-libraries', {
    params: { ...rest, size: pageSize },
  });
}

export function getAllRuleLibraries() {
  return request.get<ApiResponse<RuleLibrary[]>>('/rule-libraries/all');
}

export function createRuleLibrary(params: { name: string; description?: string }) {
  return request.post<ApiResponse<RuleLibrary>>('/rule-libraries', params);
}

export function updateRuleLibrary(id: number, params: { name: string; description?: string }) {
  return request.put<ApiResponse<RuleLibrary>>(`/rule-libraries/${id}`, params);
}

export function deleteRuleLibrary(id: number) {
  return request.delete<ApiResponse<null>>(`/rule-libraries/${id}`);
}
