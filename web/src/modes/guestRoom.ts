/** 公网/局域网访客：握手与加密由 GuestSession 封装，本类只负责传输接线与状态订阅。 */
import { GuestSession } from '../core/session';
import { LanTransport } from '../net/lan';
import { PeerTransport } from '../net/peer';
import type { GameAction } from '../net/types';
import { friendlyError } from './errors';
import { RoomSession } from './room';

export interface GuestOptions {
  code: string;
  password: string;
  name: string;
  /** 传入 ws:// 地址表示走局域网大厅（同源或手输地址）。 */
  lanUrl?: string | null;
}

export class GuestRoom extends RoomSession {
  readonly role = 'guest' as const;
  private readonly session: GuestSession;
  private readonly transport: PeerTransport | LanTransport;
  private readonly lan: boolean;

  constructor(options: GuestOptions) {
    super();
    this.session = new GuestSession(options.code, options.password, options.name, {
      onRoom: (snapshot) => this.emit({ snapshot }),
      onStatus: (status) => this.emit({ status }),
      onClosed: (reason) => this.emit({ closed: friendlyError(reason) }),
    });
    this.lan = Boolean(options.lanUrl);
    if (this.lan) {
      const transport = new LanTransport({
        onOpen: (raw) => this.session.attach(raw),
        onMessage: (_raw, text) => this.session.handleRawMessage(text),
        onClosed: (_raw, reason) => this.session.rawClosed(reason),
        onStatus: (status) => this.emit({ status }),
      });
      this.transport = transport;
      this.emit({ status: `正在连接局域网大厅 ${options.lanUrl}` });
      transport.connect(options.lanUrl ?? LanTransport.defaultUrl());
    } else {
      const transport = new PeerTransport({
        onOpen: (raw) => this.session.attach(raw),
        onMessage: (_raw, text) => this.session.handleRawMessage(text),
        onClosed: (_raw, reason) => this.session.rawClosed(reason),
        onStatus: (status) => this.emit({ status }),
      });
      this.transport = transport;
      this.emit({ status: `正在连接房间 ${options.code}` });
      transport.join(options.code, (reason) => this.emit({ closed: friendlyError(reason) }));
    }
  }

  get isLan(): boolean {
    return this.lan;
  }

  ready(value: boolean): void {
    this.session.ready(value);
  }

  action(action: GameAction): void {
    this.session.action(action);
  }

  leave(): void {
    this.session.leave();
    this.transport.close();
    this.emit({ closed: '已离开房间' });
  }
}
