import request, { ApiResponse } from '../../../shared/api/request';

export interface LoginParams {
  /** 成员填用户名（姓名），存量平台账号填邮箱。 */
  email: string;
  /** 成员登录必填：用户名只在单位内唯一，没有单位定位不到唯一账号。 */
  unitId?: number;
  password: string;
}

export interface LoginUnit {
  id: number;
  name: string;
}

/** 登录页的单位下拉。免认证接口——选单位发生在登录之前。 */
export function getLoginUnits() {
  return request.get<ApiResponse<LoginUnit[]>>('/auth/units');
}

export interface RegisterParams {
  email: string;
  password: string;
  name: string;
}

export interface UserInfo {
  id: number;
  email?: string;
  name: string;
  role: string;
  unitId?: number;
  unitName?: string;
  username?: string;
  featureCodes?: string[];
}

export interface AuthResult {
  accessToken: string;
  refreshToken: string;
  /**
   * 首次登录（或管理员重置密码后）必须改密。后端以字符串 "true"/"false" 返回——
   * token map 是 Map&lt;String,String&gt;，这里按字符串比较，别直接当布尔用。
   */
  mustChangePassword?: string;
}

export function login(params: LoginParams) {
  return request.post<ApiResponse<AuthResult>>('/auth/login', params);
}

export function register(params: RegisterParams) {
  return request.post<ApiResponse<AuthResult>>('/auth/register', params);
}

// 后端实际返回 { accessToken, refreshToken }（双 token 轮换），原先 { token } 是错的类型签名。
export function refreshToken(refreshToken: string) {
  return request.post<ApiResponse<AuthResult>>('/auth/refresh', { refreshToken });
}

export function getUserProfile() {
  return request.get<ApiResponse<UserInfo>>('/user/me');
}

export function changePassword(params: { oldPassword: string; newPassword: string }) {
  return request.put<ApiResponse<null>>('/user/password', params);
}
