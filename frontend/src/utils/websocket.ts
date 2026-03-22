type MessageHandler = (data: TaskProgressMessage) => void;

export interface TaskProgressMessage {
  taskId: string;
  status: string;
  progress: number;
  message?: string;
}

class TaskWebSocket {
  private ws: WebSocket | null = null;
  private handlers: Map<string, MessageHandler[]> = new Map();
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  private reconnectAttempts = 0;
  private maxReconnectAttempts = 5;

  connect() {
    const token = localStorage.getItem('token');
    if (!token) return;

    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const host = window.location.host;
    const url = `${protocol}//${host}/ws/task-progress?token=${token}`;

    this.ws = new WebSocket(url);

    this.ws.onopen = () => {
      console.log('WebSocket 已连接');
      this.reconnectAttempts = 0;
    };

    this.ws.onmessage = (event) => {
      try {
        const data: TaskProgressMessage = JSON.parse(event.data);
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
