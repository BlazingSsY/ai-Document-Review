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

export const SEVERITY_COLORS: Record<string, string> = {
  high: '#f5222d',
  medium: '#fa8c16',
  low: '#52c41a',
};

export const SEVERITY_LABELS: Record<string, string> = {
  high: '高',
  medium: '中',
  low: '低',
};

export const SEVERITY_TAG_COLORS: Record<string, string> = {
  high: 'red',
  medium: 'orange',
  low: 'green',
};

export const MODEL_PROVIDERS = [
  { label: 'OpenAI', value: 'openai' },
  { label: 'Anthropic', value: 'anthropic' },
  { label: 'Moonshot(Kimi)', value: 'moonshot' },
  { label: '百度文心', value: 'baidu' },
  { label: '阿里通义', value: 'alibaba' },
  { label: '讯飞星火', value: 'xfyun' },
  { label: '自定义', value: 'custom' },
];

export const PAGE_SIZE = 10;
