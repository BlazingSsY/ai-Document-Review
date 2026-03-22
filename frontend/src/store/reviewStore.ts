import { create } from 'zustand';
import { ReviewTask, ReviewFinding } from '../api/reviews';

interface ReviewState {
  currentTask: ReviewTask | null;
  progress: number;
  status: string;
  documentHtml: string;
  setCurrentTask: (task: ReviewTask | null) => void;
  setProgress: (progress: number) => void;
  setStatus: (status: string) => void;
  setDocumentHtml: (html: string) => void;
  updateFindingStatus: (findingId: number, status: 'accepted' | 'rejected') => void;
  reset: () => void;
}

const useReviewStore = create<ReviewState>((set, get) => ({
  currentTask: null,
  progress: 0,
  status: 'pending',
  documentHtml: '',

  setCurrentTask: (task) => set({
    currentTask: task,
    progress: task?.progress ?? 0,
    status: task?.status ?? 'pending',
  }),

  setProgress: (progress) => set({ progress }),

  setStatus: (status) => set({ status }),

  setDocumentHtml: (html) => set({ documentHtml: html }),

  updateFindingStatus: (findingId, status) => {
    const task = get().currentTask;
    if (!task) return;
    const findings = task.findings.map((f: ReviewFinding) =>
      f.id === findingId ? { ...f, status } : f
    );
    set({ currentTask: { ...task, findings } });
  },

  reset: () => set({
    currentTask: null,
    progress: 0,
    status: 'pending',
    documentHtml: '',
  }),
}));

export default useReviewStore;
