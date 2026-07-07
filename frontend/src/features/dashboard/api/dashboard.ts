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

export function getAdminDashboard() {
  return request.get<ApiResponse<DashboardData>>('/admin/dashboard');
}
