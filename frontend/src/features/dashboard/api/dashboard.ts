import request, { ApiResponse } from '../../../shared/api/request';

export interface NameValue {
  name: string;
  value: number;
  key?: string;
}

export interface DashboardData {
  overview: {
    totalTasks: number;
    completed: number;
    processing: number;
    pending: number;
    failed: number;
    cancelled: number;
    todayTasks: number;
    totalProblems: number;
    avgProblems: number;
  };
  statusDistribution: NameValue[];
  modeDistribution: NameValue[];
  dailyTrend: { date: string; total: number; completed: number }[];
  topModels: NameValue[];
  /** 按单位统计的审查量，降序。 */
  unitDistribution: NameValue[];
  /** 按成员统计的审查量（前 N 名），名称带单位后缀以区分跨单位重名。 */
  memberDistribution: NameValue[];
  resources: {
    users: number;
    usersByRole: NameValue[];
    rules: number;
    ruleChecks: number;
    ruleLibraries: number;
    ruleFolders: number;
    scenarios: number;
    models: number;
    modelsEnabled: number;
    modelsByType: NameValue[];
  };
  generatedAt: string;
}

/**
 * @param unitId 按单位筛选；单位管理员传什么都会被后端收敛到本单位
 * @param userId 按成员筛选
 */
export function getAdminDashboard(params?: { unitId?: number; userId?: number }) {
  return request.get<ApiResponse<DashboardData>>('/admin/dashboard', { params });
}
