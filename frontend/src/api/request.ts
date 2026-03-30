import axios, { AxiosError, InternalAxiosRequestConfig } from 'axios';
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
  (error: AxiosError<ApiResponse>) => {
    if (error.response?.status === 401) {
      localStorage.removeItem('token');
      localStorage.removeItem('user');
      window.location.href = '/login';
      message.error('登录已过期，请重新登录');
    } else {
      const msg = error.response?.data?.msg || error.message || '网络错误';
      message.error(msg);
    }
    return Promise.reject(error);
  },
);

export default request;
