import request, { ApiResponse } from './request';
import type {
  Rule, RuleLibrary, RuleListParams, RuleMetadataUpdate, ChecklistImportResult,
  PaginatedResult, RuleUploadConflict,
} from './rules';

// Types are shape-identical to chunk side.
export type {
  Rule, RuleLibrary, RuleListParams, RuleMetadataUpdate, ChecklistImportResult,
  PaginatedResult, RuleCheck, RuleFolder, RuleContentUpdate, RuleUploadConflict,
} from './rules';
import type { RuleFolder, RuleContentUpdate } from './rules';

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

export function getUploadConflicts(params: {
  fileName: string;
  libraryId?: number;
  folderId?: number;
  checklist?: boolean;
}) {
  return request.get<ApiResponse<RuleUploadConflict[]>>(`${RULES_BASE}/upload-conflicts`, { params });
}

export function updateRuleMetadata(id: number, payload: RuleMetadataUpdate) {
  return request.put<ApiResponse<Rule>>(`${RULES_BASE}/${id}/metadata`, payload);
}

export function updateRuleContent(id: number, payload: RuleContentUpdate) {
  return request.put<ApiResponse<Rule>>(`${RULES_BASE}/${id}/content`, payload);
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

// Rule Folder (二级文件夹) APIs
export function getFolderList(libraryId: number) {
  return request.get<ApiResponse<RuleFolder[]>>(`${LIBRARIES_BASE}/${libraryId}/folders`);
}

export function createFolder(libraryId: number, name: string) {
  return request.post<ApiResponse<RuleFolder>>(`${LIBRARIES_BASE}/${libraryId}/folders`, { name });
}

export function updateFolder(folderId: number, payload: { name?: string; enabled?: boolean }) {
  return request.put<ApiResponse<RuleFolder>>(`${LIBRARIES_BASE}/folders/${folderId}`, payload);
}

export function deleteFolder(folderId: number) {
  return request.delete<ApiResponse<null>>(`${LIBRARIES_BASE}/folders/${folderId}`);
}
