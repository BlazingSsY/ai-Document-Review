import request, { ApiResponse } from '../../../shared/api/request';

export interface Rule {
  id: number;
  ruleName: string;
  fileType: string;
  content?: string;
  creatorId: number;
  libraryId?: number;
  folderId?: number;
  updatedAt: string;
  isValid: boolean;

  // Editable metadata. Auto-filled on upload (frontmatter / DO-160G auto-detection);
  // overridable via PUT /api/v1/rules/{id}/metadata.
  ruleCode?: string;
  ruleType?: string;
  documentType?: string;
  sections?: string[];
  keywords?: string[];
  description?: string;
  sourceFile?: string;
  checks?: RuleCheck[];
}

export interface RuleCheck {
  id: number;
  ruleId: number;
  checkCode: string;
  checkType: string;
  question: string;
  passCriteria: string;
  category?: string;
  evidenceRequired: boolean;
  displayOrder: number;
  isActive: boolean;
}

export interface ChecklistImportResult {
  sourceFile: string;
  generatedRuleFile: string;
  ruleCode: string;
  ruleCount: number;
  checkCount: number;
  canonicalJson: string;
  importedRules: Rule[];
}

export interface RuleUploadConflict {
  id: number;
  ruleName: string;
  ruleCode?: string;
  sourceFile: string;
  libraryId?: number;
  folderId?: number;
  updatedAt?: string;
}

export interface RuleMetadataUpdate {
  ruleName?: string;
  ruleCode?: string;
  ruleType?: string;
  documentType?: string;
  sections?: string[];
  keywords?: string[];
  description?: string;
}

/** 规则内容编辑负载：content 为空不改正文；checks 为非空数组（含空数组）时整体替换检查项。 */
export interface RuleContentUpdate {
  content?: string;
  checks?: Partial<RuleCheck>[];
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
  folderId?: number;
  /** 只取未分类规则（folder_id 为空）。优先级高于 folderId。 */
  uncategorized?: boolean;
  keyword?: string;
}

/** 规则二级文件夹（规则库下的分组，可整组启用/停用）。 */
export interface RuleFolder {
  id: number;
  libraryId: number;
  name: string;
  enabled: boolean;
  ruleCount: number;
  createdAt: string;
  updatedAt: string;
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

export function importChecklist(formData: FormData) {
  return request.post<ApiResponse<ChecklistImportResult>>('/rules/import-checklist', formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
  });
}

export function getUploadConflicts(params: {
  fileName: string;
  libraryId?: number;
  folderId?: number;
  checklist?: boolean;
}) {
  return request.get<ApiResponse<RuleUploadConflict[]>>('/rules/upload-conflicts', { params });
}

export function updateRuleMetadata(id: number, payload: RuleMetadataUpdate) {
  return request.put<ApiResponse<Rule>>(`/rules/${id}/metadata`, payload);
}

export function updateRuleContent(id: number, payload: RuleContentUpdate) {
  return request.put<ApiResponse<Rule>>(`/rules/${id}/content`, payload);
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

// Rule Folder (二级文件夹) APIs
export function getFolderList(libraryId: number) {
  return request.get<ApiResponse<RuleFolder[]>>(`/rule-libraries/${libraryId}/folders`);
}

export function createFolder(libraryId: number, name: string) {
  return request.post<ApiResponse<RuleFolder>>(`/rule-libraries/${libraryId}/folders`, { name });
}

export function updateFolder(folderId: number, payload: { name?: string; enabled?: boolean }) {
  return request.put<ApiResponse<RuleFolder>>(`/rule-libraries/folders/${folderId}`, payload);
}

export function deleteFolder(folderId: number) {
  return request.delete<ApiResponse<null>>(`/rule-libraries/folders/${folderId}`);
}
