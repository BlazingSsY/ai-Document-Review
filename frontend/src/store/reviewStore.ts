import { create } from 'zustand';
import { ReviewTask } from '../api/reviews';

interface ReviewState {
  currentTask: ReviewTask | null;
  progress: number;
  status: string;
  documentHtml: string;
  setCurrentTask: (task: ReviewTask | null) => void;
  setProgress: (progress: number) => void;
  setStatus: (status: string) => void;
  setDocumentHtml: (html: string) => void;
  reset: () => void;
}

const useReviewStore = create<ReviewState>((set) => ({
  currentTask: null,
  progress: 0,
  status: 'pending',
  documentHtml: '',

  setCurrentTask: (task) => set({
    currentTask: task,
    status: task?.status?.toLowerCase() ?? 'pending',
  }),

  setProgress: (progress) => set({ progress }),

  setStatus: (status) => set({ status }),

  setDocumentHtml: (html) => set({ documentHtml: html }),

  reset: () => set({
    currentTask: null,
    progress: 0,
    status: 'pending',
    documentHtml: '',
  }),
}));

export default useReviewStore;
