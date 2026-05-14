import axios, { AxiosError, AxiosRequestConfig, InternalAxiosRequestConfig } from 'axios';
import { message } from 'antd';

export interface ApiResponse<T = unknown> {
  code: number;
  msg: string;
  data: T;
}

const request = axios.create({
  baseURL: '/api/v1',
  timeout: 30000,
  headers: {
    'Content-Type': 'application/json',
  },
});

request.interceptors.request.use(
  (config: InternalAxiosRequestConfig) => {
    const token = localStorage.getItem('token');
    if (token && config.headers) {
      config.headers.Authorization = `Bearer ${token}`;
    }
    return config;
  },
  (error) => Promise.reject(error),
);

/**
 * 静默刷新 access token 的全局单例 Promise。
 * 当 N 个并发请求同时 401 时，只触发一次 /auth/refresh，所有请求等同一个 Promise，
 * 拿到新 token 后再一起重放，避免 N 倍的刷新调用并防止 refresh token 提前轮换失效。
 */
let refreshPromise: Promise<string> | null = null;

/**
 * 用 refresh token 换一对新的 access/refresh，写回 localStorage。失败时清空鉴权信息。
 *
 * 注意这里直接用裸 axios 调 /api/v1/auth/refresh，绕开当前 request 实例的拦截器，
 * 否则刷新请求本身 401 会再次进入下面的 onRejected 形成递归。
 */
async function performRefresh(): Promise<string> {
  const rt = localStorage.getItem('refreshToken');
  if (!rt) {
    throw new Error('No refresh token available');
  }
  const res = await axios.post<ApiResponse<{ accessToken: string; refreshToken: string }>>(
    '/api/v1/auth/refresh',
    { refreshToken: rt },
    { headers: { 'Content-Type': 'application/json' }, timeout: 15000 },
  );
  const body = res.data;
  if (body?.code !== 200 || !body.data?.accessToken) {
    throw new Error(body?.msg || 'Refresh failed');
  }
  localStorage.setItem('token', body.data.accessToken);
  localStorage.setItem('refreshToken', body.data.refreshToken);
  return body.data.accessToken;
}

function getOrStartRefresh(): Promise<string> {
  if (!refreshPromise) {
    refreshPromise = performRefresh().finally(() => {
      // 让下一次 401 能够重新发起刷新（无论这次成功还是失败）
      refreshPromise = null;
    });
  }
  return refreshPromise;
}

function clearAuthAndRedirect() {
  localStorage.removeItem('token');
  localStorage.removeItem('refreshToken');
  localStorage.removeItem('user');
  // 避免在登录页本身又跳一次
  if (!window.location.pathname.startsWith('/login')) {
    window.location.href = '/login';
  }
}

// 标记请求是否已经因为 401 重试过一次，防止"刷新成功但新 token 立刻又被服务端拒"造成的死循环
type RetryableConfig = AxiosRequestConfig & { _retried?: boolean };

request.interceptors.response.use(
  (response) => {
    // Skip JSON parsing for blob responses (e.g. Excel export)
    if (response.config.responseType === 'blob') {
      return response;
    }
    const res = response.data as ApiResponse;
    if (res.code !== 200) {
      message.error(res.msg || '请求失败');
      return Promise.reject(new Error(res.msg || '请求失败'));
    }
    return response;
  },
  async (error: AxiosError<ApiResponse>) => {
    const original = error.config as RetryableConfig | undefined;
    const status = error.response?.status;

    // 401：尝试用 refresh token 静默换新，然后重放原请求
    // - 没有 original config（极端情况）或已经重试过 → 直接走登出
    // - /auth/refresh 自己 401 → 不进入这里（它走的是裸 axios，没装拦截器）
    if (status === 401 && original && !original._retried) {
      original._retried = true;
      try {
        const newToken = await getOrStartRefresh();
        original.headers = { ...(original.headers || {}), Authorization: `Bearer ${newToken}` };
        return request(original);
      } catch {
        // refresh token 也失效了（超过 7 天未活跃）：这才是真正应该回登录页的场景
        clearAuthAndRedirect();
        message.error('登录已过期，请重新登录');
        return Promise.reject(error);
      }
    }

    if (status === 401) {
      // 已经重试过仍 401 — 说明刷新得到的 token 也被服务端拒，退到登录页
      clearAuthAndRedirect();
      message.error('登录已过期，请重新登录');
    } else {
      const msg = error.response?.data?.msg || error.message || '网络错误';
      message.error(msg);
    }
    return Promise.reject(error);
  },
);

export default request;
