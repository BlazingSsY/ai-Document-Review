export const STATUS_LABELS: Record<string, string> = {
  pending: '待处理',
  processing: '处理中',
  completed: '已完成',
  failed: '失败',
  cancelled: '已取消',
  accepted: '已采纳',
  rejected: '已拒绝',
};

export const STATUS_COLORS: Record<string, string> = {
  pending: 'default',
  processing: 'processing',
  completed: 'success',
  failed: 'error',
  cancelled: 'warning',
  accepted: 'success',
  rejected: 'warning',
};

export const MODEL_PROVIDERS = [
  { label: 'Moonshot', value: 'moonshot' },
  { label: '阿里千问', value: 'alibaba' },
  { label: 'DeepSeek', value: 'deepseek' },
  { label: 'MiniMax', value: 'minimax' },
  { label: 'GLM', value: 'glm' },
  { label: '自定义', value: 'custom' },
  { label: '本地模型', value: 'local' },
];

export const MODEL_TYPES = [
  { label: '审查大模型', value: 'chat' },
  { label: 'Embedding 模型', value: 'embedding' },
  { label: 'Reranker 模型', value: 'reranker' },
];

export const PAGE_SIZE = 10;

/**
 * 审查类别（业务域）——一次审查审的是什么文件。
 *
 * 与「审查方式」（CHUNK 全文逐章 / SAR 结构化精准）是正交的两个维度：类别回答「审的是
 * 什么」，方式回答「用什么方法审」。同一类别可以走任一条管线。
 *
 * enabled=false 的是已规划、尚未实现的类别，在新建弹窗里置灰展示，让用户看得到系统的
 * 能力边界与后续方向。这份清单要和后端 ReviewCategory 的取值保持一致。
 */
export interface ReviewCategoryOption {
  value: string;
  label: string;
  description: string;
  enabled: boolean;
}

export const REVIEW_CATEGORIES: ReviewCategoryOption[] = [
  {
    value: 'ENV_TEST_OUTLINE',
    label: '环境试验大纲审查',
    description: 'DO-160G / QTP 等环境鉴定试验大纲的合规性审查',
    enabled: true,
  },
  {
    value: 'TEST_REPORT',
    label: '试验报告审查',
    description: '试验报告的数据完整性与结论一致性审查',
    enabled: false,
  },
  {
    value: 'RELIABILITY',
    label: '可靠性审查',
    description: '可靠性分析报告与预计报告的审查',
    enabled: false,
  },
];

export const DEFAULT_REVIEW_CATEGORY = 'ENV_TEST_OUTLINE';

/** 类别编码 → 中文名。未知编码原样返回，避免历史数据显示成空白。 */
export function reviewCategoryLabel(value: string | undefined | null): string {
  if (!value) return '环境试验大纲审查';
  return REVIEW_CATEGORIES.find((item) => item.value === value)?.label ?? value;
}
