import { Navigate } from 'react-router-dom';
import useAuthStore from '../../features/auth/store/authStore';

interface FeatureProtectedRouteProps {
  featureCode: string;
  children: React.ReactNode;
}

function FeatureProtectedRoute({ featureCode, children }: FeatureProtectedRouteProps) {
  const user = useAuthStore((state) => state.user);
  // 升级前写入 localStorage 的用户资料没有 featureCodes；先让页面挂载，AppLayout 会
  // 立即从 /user/me 刷新真实权限，服务端过滤器同时保证这段兼容窗口不能越权调用接口。
  if (user && user.featureCodes === undefined) return <>{children}</>;
  const allowed = user?.role === 'supervisor' || user?.featureCodes?.includes(featureCode);
  return allowed ? <>{children}</> : <Navigate to="/dashboard" replace />;
}

export default FeatureProtectedRoute;
