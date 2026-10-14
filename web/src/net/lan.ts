import { newId, type Link, type LinkHandlers } from './link';

export interface LanTransportHandlers extends LinkHandlers {
  onStatus(status: string): void;
}

/** 同一 Wi-Fi/热点内的本地大厅：网页由安卓房主提供页面，经同源 WebSocket 加入房间。 */
export class LanTransport {
  private socket: WebSocket | null = null;
  private link: Link | null = null;
  private closed = false;

  constructor(private readonly handlers: LanTransportHandlers) {}

  static defaultUrl(): string {
    const scheme = location.protocol === 'https:' ? 'wss:' : 'ws:';
    return `${scheme}//${location.host}/room`;
  }

  connect(url: string = LanTransport.defaultUrl()): void {
    if (this.closed) return;
    let socket: WebSocket;
    try {
      socket = new WebSocket(url);
    } catch {
      this.handlers.onStatus('无法打开本地大厅连接');
      return;
    }
    socket.binaryType = 'arraybuffer';
    this.socket = socket;
    const link: Link = {
      id: newId(),
      send: (text: string) => {
        if (socket.readyState === WebSocket.OPEN) {
          try { socket.send(text); } catch { /* 关闭时忽略 */ }
        }
      },
      close: () => {
        try { socket.close(1000, 'leaving'); } catch { /* ignore */ }
      },
    };
    this.link = link;
    socket.onopen = () => this.handlers.onOpen(link);
    socket.onmessage = (event) => {
      if (typeof event.data === 'string') this.handlers.onMessage(link, event.data);
    };
    socket.onclose = (event) => {
      this.link = null;
      const reason = event.reason || (event.wasClean ? null : '本地大厅连接中断');
      this.handlers.onClosed(link, reason);
    };
    socket.onerror = () => {
      this.handlers.onStatus('本地大厅连接失败');
    };
  }

  close(): void {
    if (this.closed) return;
    this.closed = true;
    try { this.socket?.close(1000, 'closed'); } catch { /* ignore */ }
    this.socket = null;
    this.link = null;
  }
}
