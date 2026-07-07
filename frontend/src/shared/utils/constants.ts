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
