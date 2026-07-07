type MessageHandler = (data: TaskProgressMessage) => void;

export interface TaskProgressMessage {
  taskId: string;
  status: string;
  progress: number;
  message?: string;
  timestamp?: number;
}

class TaskWebSocket {
  private ws: WebSocket | null = null;
  private handlers: Map<string, MessageHandler[]> = new Map();
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  private reconnectAttempts = 0;
  private maxReconnectAttempts = 5;
  // Belt-and-suspenders dedupe: even if the connect()/subscribe() guards miss
  // an edge case, the backend stamps every broadcast with a timestamp. Drop
  // any message whose key has been seen recently.
  private seenMessageKeys: Set<string> = new Set();
  private seenMessageQueue: string[] = [];
  private static readonly DEDUPE_WINDOW = 200;

  connect() {
    const token = localStorage.getItem('token');
    if (!token) return;

    // Idempotent: reuse the existing connection if it's already open or connecting.
    // Without this guard each navigation/strict-mode remount would spawn a new
    // WebSocket; old sockets stay open and all of them dispatch every broadcast,
    // which is what causes duplicated log lines in the workspace.
    if (this.ws && (this.ws.readyState === WebSocket.OPEN || this.ws.readyState === WebSocket.CONNECTING)) {
      return;
    }

    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const host = window.location.host;
    // 子路径部署：WS 也带上 Vite BASE_URL 前缀（根路径为 /，挂载时如 /office-app/）。
    const base = import.meta.env.BASE_URL;
    const url = `${protocol}//${host}${base}ws/task-progress?token=${token}`;

    this.ws = new WebSocket(url);

    this.ws.onopen = () => {
      console.log('WebSocket 已连接');
      this.reconnectAttempts = 0;
    };

    this.ws.onmessage = (event) => {
      try {
        const data: TaskProgressMessage = JSON.parse(event.data);
        const key = `${data.taskId}|${data.timestamp ?? ''}|${data.status}|${data.progress ?? ''}|${data.message ?? ''}`;
        if (this.seenMessageKeys.has(key)) {
          return;
        }
        this.seenMessageKeys.add(key);
        this.seenMessageQueue.push(key);
        if (this.seenMessageQueue.length > TaskWebSocket.DEDUPE_WINDOW) {
          const evicted = this.seenMessageQueue.shift();
          if (evicted) this.seenMessageKeys.delete(evicted);
        }

        const taskHandlers = this.handlers.get(data.taskId);
        if (taskHandlers) {
          taskHandlers.forEach((handler) => handler(data));
        }
        // Also notify global handlers
        const globalHandlers = this.handlers.get('*');
        if (globalHandlers) {
          globalHandlers.forEach((handler) => handler(data));
        }
      } catch (e) {
        console.error('WebSocket 消息解析失败:', e);
      }
    };

    this.ws.onclose = () => {
      console.log('WebSocket 已断开');
      this.tryReconnect();
    };

    this.ws.onerror = (error) => {
      console.error('WebSocket 错误:', error);
    };
  }

  private tryReconnect() {
    if (this.reconnectAttempts >= this.maxReconnectAttempts) return;
    this.reconnectAttempts++;
    const delay = Math.min(1000 * Math.pow(2, this.reconnectAttempts), 30000);
    this.reconnectTimer = setTimeout(() => this.connect(), delay);
  }

  subscribe(taskId: string, handler: MessageHandler) {
    const existing = this.handlers.get(taskId) || [];
    if (existing.includes(handler)) return;
    existing.push(handler);
    this.handlers.set(taskId, existing);
  }

  unsubscribe(taskId: string, handler?: MessageHandler) {
    if (!handler) {
      this.handlers.delete(taskId);
    } else {
      const existing = this.handlers.get(taskId) || [];
      this.handlers.set(taskId, existing.filter((h) => h !== handler));
    }
  }

  disconnect() {
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer);
    }
    if (this.ws) {
      this.ws.close();
      this.ws = null;
    }
    this.handlers.clear();
  }
}

const taskWebSocket = new TaskWebSocket();
export default taskWebSocket;
