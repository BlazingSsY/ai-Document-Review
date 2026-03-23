import request, { ApiResponse } from './request';
import { PaginatedResult } from './rules';
import { UserInfo } from './auth';

export function getUserList(params: { page: number; pageSize: number }) {
  const { pageSize, ...rest } = params;
  return request.get<ApiResponse<PaginatedResult<UserInfo>>>('/admin/users', {
    params: { ...rest, size: pageSize },
  });
}

export function createUser(params: { email: string; password: string; name: string; role: string }) {
  return request.post<ApiResponse<UserInfo>>('/admin/users', params);
}

export function updateUserRole(userId: number, role: string) {
  return request.put<ApiResponse<null>>(`/admin/users/${userId}/role`, { role });
}

export function assignLibraries(userId: number, libraryIds: number[]) {
  return request.post<ApiResponse<null>>(`/admin/users/${userId}/libraries`, { libraryIds });
}

export function getUserAssignedLibraries(userId: number) {
  return request.get<ApiResponse<number[]>>(`/admin/users/${userId}/libraries`);
}
