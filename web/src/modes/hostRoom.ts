/** 公网房主：PeerJS 信令 + WebRTC 直连，HostSession 为唯一权威。 */
import { HostSession } from '../core/session';
import { PeerTransport } from '../net/peer';
import type { Link } from '../net/link';
import type { SecureLink } from '../net/secure';
import type { GameAction } from '../net/types';
import { friendlyError } from './errors';
import { RoomSession } from './room';

export const DEFAULT_ROOM_GAME = 'gomoku';

export class HostRoom extends RoomSession {
  readonly role = 'host' as const;
  private readonly transport: PeerTransport;
  private readonly session: HostSession;
  private readonly links = new Map<string, SecureLink>();

  constructor(
    readonly code: string,
    readonly password: string,
    hostName: string,
  ) {
    super();
    this.session = new HostSession(code, DEFAULT_ROOM_GAME, 2, password, hostName, {
      onRoom: (snapshot) => this.emit({ snapshot }),
      onMembersChanged: () => undefined,
      onStatus: (status) => this.emit({ status }),
      onClosed: (reason) => this.emit({ closed: friendlyError(reason) }),
    });
    this.transport = new PeerTransport({
      onOpen: (raw: Link) => {
        this.links.set(raw.id, this.session.registerRaw(raw));
      },
      onMessage: (raw: Link, text: string) => {
        this.session.handleRawMessage(raw.id, text);
      },
      onClosed: (raw: Link, reason: string | null) => {
        this.links.delete(raw.id);
        this.session.rawClosed(raw.id, reason);
      },
      onStatus: (status) => this.emit({ status }),
    });
    this.transport.host(code);
    this.emit({ snapshot: this.session.snapshot(), status: '正在连接信令服务器' });
  }

  chooseGame(gameId: string, players: number): boolean {
    const changed = this.session.chooseGame(gameId, players);
    if (!changed) this.emit({ status: '无法切换：人数需符合该游戏规则，且对局尚未开始' });
    return changed;
  }

  startMatch(difficulty: number): boolean {
    const seed = Math.floor(Math.random() * 2_147_483_647);
    const started = this.session.start(seed, difficulty);
    if (!started) this.emit({ status: '无法开始：请确认所有玩家都已准备、连接正常，且人数符合要求' });
    return started;
  }

  applyHostAction(action: GameAction): boolean {
    return this.session.applyHostAction(action);
  }

  returnToLobby(): boolean {
    return this.session.returnToLobby();
  }

  leave(): void {
    this.session.close();
    this.transport.close();
    this.emit({ closed: '已离开房间' });
  }
}
