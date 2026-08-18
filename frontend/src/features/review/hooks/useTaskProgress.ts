import { useEffect, useRef, useState } from 'react';
import type { ReviewTask } from '../api/reviews';
import taskWebSocket, { TaskProgressMessage } from '../../../shared/utils/websocket';
import useLogStore from '../store/logStore';

const TERMINAL_STATUSES = new Set(['COMPLETED', 'FAILED', 'CANCELLED']);

/**
 * 任务列表页共用的实时进度表：taskId → 0~100。
 *
 * 三个数据来源合流，缺一都会让进度条在某个场景下空白几秒：
 *  1. WebSocket 帧——正常推进；
 *  2. 常驻的 logStore——从详情页返回时本组件重挂、本地 state 归零，读它一次即可立刻点亮；
 *  3. 列表接口返回的 task.progress——硬刷新后 logStore 也空了，用后端的最近进度兜底。
 *
 * 进度按单调递增合并，乱序到达的旧帧不会把进度条拽回去。
 *
 * @param enabled  无审查权限时不必订阅，传 false 即可关掉。
 * @param onSettle 任务进入终态时回调，通常用来刷新列表与统计。
 */
export function useTaskProgress(enabled: boolean, onSettle?: () => void) {
  const [progress, setProgress] = useState<Record<string, number>>({});
  // 回调每次渲染都是新函数；用 ref 持有，避免订阅被反复拆装。
  const settleRef = useRef(onSettle);
  settleRef.current = onSettle;

  /** 用已知进度回填；已有的实时值优先，不会把更新的进度盖回旧值。 */
  const seed = (seeded: Record<string, number>) => {
    if (Object.keys(seeded).length > 0) {
      setProgress((prev) => ({ ...seeded, ...prev }));
    }
  };

  /** 从列表接口返回的任务里取 progress 回填（硬刷新场景）。 */
  const seedFromTasks = (tasks: ReviewTask[]) => {
    const seeded: Record<string, number> = {};
    for (const task of tasks) {
      if (typeof task.progress === 'number') seeded[task.id] = task.progress;
    }
    seed(seeded);
  };

  // 挂载时从常驻 logStore 取每个任务的最近一次进度。
  useEffect(() => {
    const seeded: Record<string, number> = {};
    for (const [taskId, entries] of Object.entries(useLogStore.getState().logsByTask)) {
      for (let i = entries.length - 1; i >= 0; i--) {
        if (typeof entries[i].progress === 'number') {
          seeded[taskId] = entries[i].progress as number;
          break;
        }
      }
    }
    seed(seeded);
  }, []);

  useEffect(() => {
    if (!enabled) return undefined;
    taskWebSocket.connect();
    const handler = (data: TaskProgressMessage) => {
      if (!data.taskId) return;
      if (TERMINAL_STATUSES.has(data.status?.toUpperCase())) {
        // 终态：丢掉该任务的进度，避免行已翻牌进度条还挂着。
        setProgress((prev) => {
          if (!(data.taskId in prev)) return prev;
          const next = { ...prev };
          delete next[data.taskId];
          return next;
        });
        settleRef.current?.();
      } else if (typeof data.progress === 'number') {
        setProgress((prev) => (
          data.progress <= (prev[data.taskId] ?? 0)
            ? prev
            : { ...prev, [data.taskId]: data.progress }
        ));
      }
    };
    taskWebSocket.subscribe('*', handler);
    return () => { taskWebSocket.unsubscribe('*', handler); };
  }, [enabled]);

  return { progress, seedFromTasks };
}

export default useTaskProgress;
