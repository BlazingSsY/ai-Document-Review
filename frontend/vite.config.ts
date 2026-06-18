import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// 子路径部署：构建时传 APP_BASE（如 /office-app/）即可把整套前端挂到该前缀下；
// 不传则默认根路径 /。base 会驱动资源前缀与 import.meta.env.BASE_URL（路由/接口/WS 均据此拼接）。
export default defineConfig({
  base: process.env.APP_BASE || '/',
  plugins: [react()],
  server: {
    port: 3000,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
      '/ws': {
        target: 'ws://localhost:8080',
        ws: true,
      },
    },
  },
});
