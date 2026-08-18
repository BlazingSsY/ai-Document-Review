import type { ReactNode } from 'react';
import { ExperimentOutlined, FileDoneOutlined, SafetyOutlined } from '@ant-design/icons';
import type { ReviewMode } from '../api/reviews';

/**
 * 一个「审查功能」= 一个业务域，对应后端 {@code ReviewFeature} 的一次注册。
 *
 * 前端的菜单、路由、快捷入口、新建审查弹窗全部由这张表推导，新增一个审查功能只需要在
 * REVIEW_FEATURES 里加一条 + 后端注册对应的 ReviewFeature bean，不必去改 AppLayout /
 * App.tsx / DashboardPage 里的任何硬编码分支——这是「新增试验报告审查不影响现有试验
 * 大纲审查」的结构保证。
 *
 * category / permissionCode 必须与后端 {@code ReviewFeature#category()} 与
 * {@code #permissionCode()} 逐字一致，否则任务会落到错误的类别或鉴权失败。
 */
export interface ReviewFeatureDef {
  /** 审查类别编码，与后端一致。任务按它隔离列表与统计。 */
  category: string;
  /** 功能授权编码，与后端一致。决定菜单可见性与路由守卫。 */
  permissionCode: string;
  /** 路由片段：任务中心挂在 /reviews/{slug}。 */
  slug: string;
  /** 菜单分组名与页面标题。 */
  label: string;
  /** 标签徽章上的短名。 */
  shortLabel: string;
  description: string;
  icon: ReactNode;
  /** 新建审查弹窗的副标题，说明这个业务域审的是什么。 */
  createHint: string;
  /** 该功能使用的审查管线。前端目前只开放 CHUNK。 */
  reviewMode: ReviewMode;
  /**
   * 是否复用共享的规则库 / 场景（对应后端 {@code usesSharedRuleLibraries}）。
   * 为 false 的功能需要自带配置页，届时在这里扩展配置入口的描述。
   */
  usesSharedRuleLibraries: boolean;
  /** false = 已规划未上线：不进菜单、不注册路由，仅用于向用户展示能力边界。 */
  enabled: boolean;
}

export const REVIEW_FEATURES: ReviewFeatureDef[] = [
  {
    category: 'ENV_TEST_OUTLINE',
    permissionCode: 'ENV_TEST_OUTLINE_REVIEW',
    slug: 'env-outline',
    label: '环境试验大纲审查',
    shortLabel: '环境试验大纲',
    description: 'DO-160G / QTP 等环境鉴定试验大纲的合规性审查',
    icon: <ExperimentOutlined />,
    createHint: '上传环境试验大纲，系统将按章节自动拆分并执行规则审查',
    reviewMode: 'CHUNK',
    usesSharedRuleLibraries: true,
    enabled: true,
  },
  {
    category: 'TEST_REPORT',
    permissionCode: 'TEST_REPORT_REVIEW',
    slug: 'test-report',
    label: '试验报告审查',
    shortLabel: '试验报告',
    description: '试验报告的数据完整性与结论一致性审查',
    icon: <FileDoneOutlined />,
    createHint: '上传试验报告，系统将逐章核对数据、结论与大纲要求的一致性',
    reviewMode: 'CHUNK',
    usesSharedRuleLibraries: true,
    enabled: false,
  },
  {
    category: 'RELIABILITY',
    permissionCode: 'RELIABILITY_REVIEW',
    slug: 'reliability',
    label: '可靠性审查',
    shortLabel: '可靠性',
    description: '可靠性分析报告与预计报告的审查',
    icon: <SafetyOutlined />,
    createHint: '上传可靠性分析或预计报告，系统将按规则库执行合规性审查',
    reviewMode: 'CHUNK',
    usesSharedRuleLibraries: true,
    enabled: false,
  },
];

/** 未显式指定类别时使用的功能，与后端 defaultFeature 对应。 */
export const DEFAULT_REVIEW_FEATURE = REVIEW_FEATURES[0];

/** 已上线的功能。未上线的只在能力清单里出现，不进菜单、不注册路由。 */
export const ENABLED_REVIEW_FEATURES = REVIEW_FEATURES.filter((feature) => feature.enabled);

/** 任务中心路由。 */
export function reviewFeaturePath(feature: ReviewFeatureDef): string {
  return `/reviews/${feature.slug}`;
}

export function findReviewFeatureBySlug(slug: string | undefined): ReviewFeatureDef | undefined {
  return ENABLED_REVIEW_FEATURES.find((feature) => feature.slug === slug);
}

/**
 * 类别编码 → 中文名。未知编码原样返回，避免历史数据或后端新注册但前端还没登记的
 * 类别显示成空白。
 */
export function reviewCategoryLabel(category: string | undefined | null): string {
  if (!category) return DEFAULT_REVIEW_FEATURE.label;
  return REVIEW_FEATURES.find((feature) => feature.category === category)?.label ?? category;
}

/**
 * 一组功能授权码里是否包含「复用共享规则库」的审查功能。
 * 对应后端 {@code FeaturePermissionService#includesSharedRuleLibraryFeature}——
 * 决定成员授权弹窗要不要展示规则库勾选区。
 */
export function usesSharedRuleLibraries(featureCodes: string[] | undefined | null): boolean {
  if (!featureCodes || featureCodes.length === 0) return false;
  return ENABLED_REVIEW_FEATURES.some((feature) => (
    feature.usesSharedRuleLibraries && featureCodes.includes(feature.permissionCode)
  ));
}

/** 一组功能授权码对应的审查功能名称，用于成员列表里展示已分配功能。 */
export function reviewFeatureLabels(featureCodes: string[] | undefined | null): string[] {
  if (!featureCodes || featureCodes.length === 0) return [];
  return ENABLED_REVIEW_FEATURES
    .filter((feature) => featureCodes.includes(feature.permissionCode))
    .map((feature) => feature.label);
}
