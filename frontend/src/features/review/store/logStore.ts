import { create } from 'zustand';
import { createJSONStorage, persist } from 'zustand/middleware';

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

interface PersistedLogState {
  logsByTask: Record<string, LogEntry[]>;
}

// Persist logs across route changes and page refreshes so navigating back into
// 查看详情 keeps the runtime timeline. Bound both per-task logs and task count so
// the browser session store cannot grow indefinitely.
const MAX_LOGS_PER_TASK = 1000;
const MAX_PERSISTED_TASKS = 50;
const LOG_STORAGE_KEY = 'ai-review-task-logs-v1';

const useLogStore = create<LogState>()(persist<LogState, [], [], PersistedLogState>((set, get) => ({
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
    const logsByTask = { ...state.logsByTask, [taskId]: next };
    const taskIds = Object.keys(logsByTask);
    if (taskIds.length > MAX_PERSISTED_TASKS) {
      delete logsByTask[taskIds[0]];
    }
    return { logsByTask };
  }),

  clearLogs: (taskId) => set((state) => {
    const { [taskId]: _drop, ...rest } = state.logsByTask;
    return { logsByTask: rest };
  }),

  getLogs: (taskId) => get().logsByTask[taskId] || [],
}), {
  name: LOG_STORAGE_KEY,
  // Review logs can contain document/chapter names. sessionStorage survives a
  // page refresh but is scoped to the current browser tab and is discarded when
  // that tab closes, avoiding indefinite cross-session retention.
  storage: createJSONStorage(() => sessionStorage),
  partialize: (state) => ({ logsByTask: state.logsByTask }),
  version: 1,
}));

export default useLogStore;
