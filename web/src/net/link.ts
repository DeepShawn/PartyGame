/** 原始连接抽象：TCP、WebSocket、WebRTC 都实现它与上层会话握手的同一契约。 */
export interface Link {
  readonly id: string;
  send(text: string): void;
  close(): void;
}

export interface LinkHandlers {
  onOpen(link: Link): void;
  onMessage(link: Link, text: string): void;
  onClosed(link: Link, reason: string | null): void;
}

export function newId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') return crypto.randomUUID();
  const bytes = new Uint8Array(16);
  crypto.getRandomValues(bytes);
  return Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('');
}
