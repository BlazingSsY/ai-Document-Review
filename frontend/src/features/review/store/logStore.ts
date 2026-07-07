import { create } from 'zustand';

export interface LogEntry {
  time: string;
  level: 'info' | 'error' | 'success' | 'warning';
  message: string;
  progress?: number;
}

interface LogState {
  logsByTask: Record<string, LogEntry[]>;
  appendLog: (taskId: string, entry: LogEntry) => void;
  clearLogs: (taskId: string) => void;
  getLogs: (taskId: string) => LogEntry[];
}

// Persist logs across route changes so navigating from the workspace back to the
// dashboard and re-entering 查看详情 keeps the full log timeline. We cap each task
// at 1000 entries so a long-running review can't grow this map without bound.
const MAX_LOGS_PER_TASK = 1000;

const useLogStore = create<LogState>((set, get) => ({
  logsByTask: {},

  appendLog: (taskId, entry) => set((state) => {
    const existing = state.logsByTask[taskId] || [];
    // Skip exact duplicates of the most recent entry (same time + level + message)
    // to suppress double-fires that happen when both the global subscriber and
    // the page subscriber receive the same WebSocket frame.
    const last = existing[existing.length - 1];
    if (last && last.time === entry.time && last.level === entry.level
        && last.message === entry.message && last.progress === entry.progress) {
      return state;
    }
    const next = existing.length >= MAX_LOGS_PER_TASK
      ? [...existing.slice(existing.length - MAX_LOGS_PER_TASK + 1), entry]
      : [...existing, entry];
    return { logsByTask: { ...state.logsByTask, [taskId]: next } };
  }),

  clearLogs: (taskId) => set((state) => {
    const { [taskId]: _drop, ...rest } = state.logsByTask;
    return { logsByTask: rest };
  }),

  getLogs: (taskId) => get().logsByTask[taskId] || [],
}));

export default useLogStore;
