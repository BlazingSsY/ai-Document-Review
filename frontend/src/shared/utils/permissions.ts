import useAuthStore from '../../features/auth/store/authStore';
import type { UserInfo } from '../../features/auth/api/auth';
import {
  ENABLED_REVIEW_FEATURES, type ReviewFeatureDef,
} from '../../features/review/registry/reviewFeatures';

/**
 * 是否拥有某项功能授权。平台管理员天然拥有全部功能。
 * 这里只决定「菜单和页面给不给看」，越权调用由后端过滤器兜底。
 */
export function hasFeature(user: UserInfo | null | undefined, featureCode: string): boolean {
  if (!user) return false;
  return user.role === 'supervisor' || Boolean(user.featureCodes?.includes(featureCode));
}

/**
 * 当前用户有权使用的审查功能，按注册表顺序返回。
 * 菜单、快捷入口、路由守卫都以它为准——新增审查功能后自动出现，无需改调用方。
 */
export function useAuthorizedReviewFeatures(): ReviewFeatureDef[] {
  const user = useAuthStore((state) => state.user);
  return ENABLED_REVIEW_FEATURES.filter((feature) => hasFeature(user, feature.permissionCode));
}

/** 是否至少有一个审查功能可用。无授权时工作台显示提示页而不是空列表。 */
export function useHasAnyReviewFeature(): boolean {
  return useAuthorizedReviewFeatures().length > 0;
}

/** 单位管理员及以上。成员与权限、数据看板等管理入口用它做可见性判断。 */
export function useIsManager(): boolean {
  const role = useAuthStore((state) => state.user?.role);
  return role === 'supervisor' || role === 'admin';
}
