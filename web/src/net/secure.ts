import { SessionCipher, type EncryptedPacket } from '../security/session';
import {
  SrpClient,
  createSrpHost,
  type AuthChallenge,
  type ClientProof,
  type ServerProof,
} from '../security/srp';
import { newId, type Link } from './link';

export interface SecureHandlers {
  onReady(link: SecureLink): void;
  onMessage(link: SecureLink, text: string): void;
  onClosed(link: SecureLink, reason: string): void;
}

const HANDSHAKE_TIMEOUT_MS = 25_000;

/**
 * 口令认证 + 加密信道。认证通过前不向对端暴露任何房间状态；
 * 通过后所有消息均为 AES-256-GCM 顺序号封包。
 */
export class SecureLink implements Link {
  readonly id = newId();
  private cipher: SessionCipher | null = null;
  private queue: Promise<void> = Promise.resolve();
  private closed = false;
  private timer: ReturnType<typeof setTimeout>;

  private constructor(
    private readonly raw: Link,
    private readonly handlers: SecureHandlers,
    private readonly roomId: string,
  ) {
    this.timer = setTimeout(() => this.fail('认证超时'), HANDSHAKE_TIMEOUT_MS);
  }

  static host(raw: Link, handlers: SecureHandlers, roomId: string, password: string): SecureLink {
    const link = new SecureLink(raw, handlers, roomId);
    void link.startHost(raw, password);
    return link;
  }

  static guest(raw: Link, handlers: SecureHandlers, roomId: string, password: string): SecureLink {
    const link = new SecureLink(raw, handlers, roomId);
    void link.startGuest(raw, password);
    return link;
  }

  private hostSession: Awaited<ReturnType<typeof createSrpHost>> | null = null;
  private guestClient: SrpClient | null = null;

  private async startHost(raw: Link, password: string): Promise<void> {
    try {
      const session = await createSrpHost(this.roomId, password);
      if (this.closed) return;
      this.hostSession = session;
      raw.send(JSON.stringify(session.challenge));
    } catch (error) {
      this.fail(`认证初始化失败: ${message(error)}`);
    }
  }

  private async startGuest(raw: Link, password: string): Promise<void> {
    try {
      const client = await SrpClient.create(this.roomId, password);
      if (this.closed) return;
      this.guestClient = client;
    } catch (error) {
      this.fail(`认证初始化失败: ${message(error)}`);
    }
  }

  handleRawMessage(text: string): void {
    void this.dispatch(text);
  }

  private async dispatch(text: string): Promise<void> {
    if (this.closed) return;
    try {
      if (!this.cipher) {
        const value = JSON.parse(text) as { type?: string };
        if (value.type === 'challenge') {
          if (!this.guestClient) throw new Error('认证状态异常');
          const proof = this.guestClient.answer(value as AuthChallenge);
          this.raw.send(JSON.stringify(proof));
          return;
        }
        if (value.type === 'proof') {
          const session = this.hostSession;
          if (!session) throw new Error('认证状态异常');
          const result = await session.verify(value as ClientProof, SessionCipher.create);
          if (this.closed) return;
          this.cipher = result.cipher;
          this.raw.send(JSON.stringify(result.proof));
          this.ready();
          return;
        }
        if (value.type === 'verified') {
          if (!this.guestClient) throw new Error('认证状态异常');
          this.cipher = await this.guestClient.finish(value as ServerProof, SessionCipher.create);
          if (this.closed) return;
          this.ready();
          return;
        }
        throw new Error('认证阶段出现非法消息');
      }
      const packet = JSON.parse(text) as EncryptedPacket;
      const plain = await this.cipher.open(packet);
      if (!this.closed) this.handlers.onMessage(this, plain);
    } catch (error) {
      this.fail(message(error));
    }
  }

  private ready(): void {
    clearTimeout(this.timer);
    this.handlers.onReady(this);
  }

  send(text: string): void {
    if (this.closed) return;
    if (!this.cipher) throw new Error('尚未完成房间认证');
    const cipher = this.cipher;
    this.queue = this.queue.then(async () => {
      if (this.closed) return;
      const packet = await cipher.seal(text);
      if (!this.closed) this.raw.send(JSON.stringify(packet));
    }).catch((error) => this.fail(message(error)));
  }

  rawClosed(reason: string | null): void {
    this.finish(reason ?? '连接已关闭');
  }

  close(): void {
    if (this.closed) return;
    try { this.raw.close(); } catch { /* ignore */ }
    this.finish('已离开房间');
  }

  private fail(reason: string): void {
    try { this.raw.close(); } catch { /* ignore */ }
    this.finish(reason);
  }

  private finish(reason: string): void {
    if (this.closed) return;
    this.closed = true;
    clearTimeout(this.timer);
    this.handlers.onClosed(this, reason);
  }
}

function message(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}
