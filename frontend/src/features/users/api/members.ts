import request, { ApiResponse } from '../../../shared/api/request';
import { PaginatedResult } from '../../rules/api/rules';

export interface Unit {
  id: number;
  name: string;
  code?: string;
  remark?: string;
  createdAt?: string;
}

export interface Member {
  id: number;
  name: string;
  username?: string;
  email?: string;
  role: string;
  unitId?: number;
  unitName?: string;
  /** 后端只返回脱敏值，如 110101********1234；完整号码不出后端。 */
  idCardMasked?: string;
  mustChangePassword?: boolean;
  createdAt?: string;
}

export interface MemberImportRow {
  rowNumber: number;
  name?: string;
  unitName?: string;
  reason?: string;
}

export interface MemberImportResult {
  successCount: number;
  failureCount: number;
  succeeded: MemberImportRow[];
  failed: MemberImportRow[];
}

const BASE = '/admin/members';

export function getUnits() {
  return request.get<ApiResponse<Unit[]>>(`${BASE}/units`);
}

export function createUnit(params: { name: string; code?: string; remark?: string }) {
  return request.post<ApiResponse<Unit>>(`${BASE}/units`, params);
}

export function deleteUnit(unitId: number) {
  return request.delete<ApiResponse<null>>(`${BASE}/units/${unitId}`);
}

export function getMembers(params: {
  page: number;
  pageSize: number;
  unitId?: number;
  keyword?: string;
}) {
  const { pageSize, ...rest } = params;
  return request.get<ApiResponse<PaginatedResult<Member>>>(BASE, {
    params: { ...rest, size: pageSize },
  });
}

export function createMember(params: {
  unitId: number;
  username: string;
  name?: string;
  idCard: string;
  role: string;
}) {
  return request.post<ApiResponse<Member>>(BASE, {
    ...params,
    unitId: String(params.unitId),
  });
}

export function updateMemberRole(memberId: number, role: string) {
  return request.put<ApiResponse<null>>(`${BASE}/${memberId}/role`, { role });
}

export function resetMemberPassword(memberId: number) {
  return request.post<ApiResponse<null>>(`${BASE}/${memberId}/reset-password`);
}

export function deleteMember(memberId: number) {
  return request.delete<ApiResponse<null>>(`${BASE}/${memberId}`);
}

/** Excel 批量导入。返回逐行明细，失败行带原因。 */
export function importMembers(file: File) {
  const formData = new FormData();
  formData.append('file', file);
  return request.post<ApiResponse<MemberImportResult>>(`${BASE}/import`, formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
    timeout: 120000,
  });
}
