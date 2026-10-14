import { Peer, type DataConnection } from 'peerjs';
import { newId, type Link, type LinkHandlers } from './link';

export interface PeerTransportHandlers extends LinkHandlers {
  onStatus(status: string): void;
}

export function roomPeerId(code: string): string {
  return `pg-party-${code.toLowerCase()}`;
}

/**
 * 基于公共免费 PeerJS Cloud 信令 + WebRTC DataChannel 的传输。
 * 仅交换连接信息，游戏数据经 DTLS 加密后点对点直达；对称 NAT 无法穿透时会明确报错。
 */
export class PeerTransport {
  private peer: Peer | null = null;
  private closed = false;
  private readonly links = new Map<string, Link>();

  constructor(private readonly handlers: PeerTransportHandlers) {}

  private create(hostCode: string | null): Peer {
    const peer = hostCode ? new Peer(roomPeerId(hostCode), { debug: 0 }) : new Peer({ debug: 0 });
    this.peer = peer;
    peer.on('open', () => this.handlers.onStatus(hostCode ? '信令已连接，等待玩家加入' : '信令已连接，正在联系房主'));
    peer.on('error', (error) => {
      const type = (error as { type?: string }).type ?? 'unknown';
      const text = {
        'peer-unavailable': '房间不存在或房主已离线',
        'unavailable-id': '房间码已被占用，请返回重试',
        network: '无法连接信令服务器（部分网络会屏蔽，可改用局域网）',
        'server-error': '信令服务器返回错误（公共免费服务可能繁忙）',
        'socket-error': '信令连接中断',
        'socket-closed': '信令连接被关闭',
        disconnected: '信令服务器连接不稳定',
        'browser-incompatible': '当前浏览器不支持 WebRTC',
      }[type] ?? `信令错误: ${type}`;
      this.handlers.onStatus(text);
      if (hostCode && (type === 'unavailable-id' || type === 'network' || type === 'server-error' || type === 'socket-error')) {
        this.close();
      }
      if (!hostCode && (type === 'peer-unavailable' || type === 'network' || type === 'server-error' || type === 'socket-error' || type === 'socket-closed')) {
        const target = this.pendingGuest;
        this.pendingGuest = null;
        target?.(text);
      }
    });
    peer.on('disconnected', () => {
      this.handlers.onStatus('信令断开，正在尝试恢复');
      if (!this.closed) {
        try { peer.reconnect(); } catch { /* ignore */ }
      }
    });
    return peer;
  }

  private pendingGuest: ((reason: string) => void) | null = null;

  host(code: string): void {
    const peer = this.create(code);
    peer.on('connection', (conn) => this.accept(conn));
  }

  join(code: string, onFailure: (reason: string) => void): void {
    const peer = this.create(null);
    this.pendingGuest = onFailure;
    peer.on('open', () => {
      if (this.closed) return;
      const conn = peer.connect(roomPeerId(code), { reliable: true, serialization: 'json', metadata: { app: 'partygame' } });
      this.accept(conn);
    });
  }

  private accept(conn: DataConnection): void {
    const link: Link = {
      id: newId(),
      send: (text: string) => {
        if (conn.open) {
          try { conn.send(text); } catch (error) { this.handlers.onClosed(link, String((error as Error)?.message ?? error)); }
        }
      },
      close: () => { try { conn.close(); } catch { /* ignore */ } },
    };
    this.links.set(link.id, link);
    this.pendingGuest = null;
    conn.on('open', () => {
      this.handlers.onOpen(link);
    });
    conn.on('data', (data: unknown) => {
      const text = typeof data === 'string' ? data : JSON.stringify(data);
      this.handlers.onMessage(link, text);
    });
    conn.on('close', () => {
      this.links.delete(link.id);
      this.handlers.onClosed(link, '连接已关闭');
    });
    conn.on('error', (error) => {
      const reason = String((error as Error)?.message ?? error ?? '连接错误');
      this.links.delete(link.id);
      this.handlers.onClosed(link, reason);
    });
  }

  close(): void {
    if (this.closed) return;
    this.closed = true;
    for (const link of this.links.values()) {
      try { link.close(); } catch { /* ignore */ }
    }
    this.links.clear();
    try { this.peer?.destroy(); } catch { /* ignore */ }
    this.peer = null;
  }
}
