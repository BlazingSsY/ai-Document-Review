import request, { ApiResponse } from './request';

export interface LoginParams {
  email: string;
  password: string;
}

export interface RegisterParams {
  email: string;
  password: string;
  name: string;
}

export interface UserInfo {
  id: number;
  email: string;
  name: string;
  role: string;
}

export interface AuthResult {
  accessToken: string;
  refreshToken: string;
}

export function login(params: LoginParams) {
  return request.post<ApiResponse<AuthResult>>('/auth/login', params);
}

export function register(params: RegisterParams) {
  return request.post<ApiResponse<AuthResult>>('/auth/register', params);
}

export function refreshToken(refreshToken: string) {
  return request.post<ApiResponse<{ token: string }>>('/auth/refresh', { refreshToken });
}

export function getUserInfo() {
  return request.get<ApiResponse<UserInfo>>('/auth/me');
}
