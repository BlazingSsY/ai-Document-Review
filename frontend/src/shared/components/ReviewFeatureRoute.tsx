import { Navigate } from 'react-router-dom';
import useAuthStore from '../../features/auth/store/authStore';
import { useAuthorizedReviewFeatures } from '../utils/permissions';

/**
 * 守卫「至少获授权一个审查功能」才能进入的页面：审查配置（场景/规则）与审查详情页。
 *
 * 这些页面跨业务域共用，不该绑定某一个功能码——否则新增试验报告审查后，只被授权了
 * 试验报告的用户会被挡在共享的规则/场景页外面。具体到某个业务域的鉴权由各自的任务
 * 中心页负责，越权调用由后端过滤器兜底。
 */
function ReviewFeatureRoute({ children }: { children: React.ReactNode }) {
  const user = useAuthStore((state) => state.user);
  const authorizedFeatures = useAuthorizedReviewFeatures();

  // 升级前写入 localStorage 的用户资料没有 featureCodes；先让页面挂载，AppLayout 会
  // 立即从 /user/me 刷新真实权限，服务端过滤器同时保证这段兼容窗口不能越权调用接口。
  if (user && user.featureCodes === undefined) return <>{children}</>;

  return authorizedFeatures.length > 0 ? <>{children}</> : <Navigate to="/dashboard" replace />;
}

export default ReviewFeatureRoute;
