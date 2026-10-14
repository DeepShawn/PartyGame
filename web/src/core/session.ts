import { parseRoomMessage, emptyMessage, PROTOCOL_VERSION, type GameAction, type RoomMessage, type RoomSnapshot } from '../net/types';
import type { Link } from '../net/link';
import { newId } from '../net/link';
import { SecureLink } from '../net/secure';
import { EngineApi, RoomApi, findGame, release } from './engine';
import { chessBestMove } from './stockfish';
import { uciToAction } from './uci';

export interface RoomEvents {
  onRoom(snapshot: RoomSnapshot): void;
  onStatus(status: string): void;
  onClosed(reason: string): void;
}

/** 网页客户端：只发送自己的操作，所有裁决在房主端。 */
export class GuestSession {
  private secure: SecureLink | null = null;
  private sequence = 0;
  private closed = false;
  private currentMatch = -1;

  constructor(
    private readonly roomId: string,
    private readonly password: string,
    private readonly name: string,
    private readonly events: RoomEvents,
  ) {}

  attach(raw: Link): void {
    this.secure = SecureLink.guest(raw, {
      onReady: (link) => {
        const join = emptyMessage('join');
        join.roomId = this.roomId;
        join.name = this.name;
        link.send(JSON.stringify(join));
        this.events.onStatus('已加入房间，等待房主确认');
      },
      onMessage: (_link, text) => this.handle(text),
      onClosed: (_link, reason) => this.finish(reason),
    }, this.roomId, this.password);
  }

  handleRawMessage(text: string): void { this.secure?.handleRawMessage(text); }
  rawClosed(reason: string | null): void { this.secure?.rawClosed(reason); }

  private handle(text: string): void {
    let message: RoomMessage;
    try {
      message = parseRoomMessage(text);
    } catch {
      this.finish('收到无法解析的房间消息');
      return;
    }
    if (message.type === 'room' && message.room) {
      this.currentMatch = message.room.matchId;
      this.events.onRoom(message.room);
    } else if (message.type === 'reject') {
      this.finish(message.message || '被房主拒绝加入');
    }
  }

  ready(value: boolean): void {
    const message = emptyMessage('ready');
    message.ready = value;
    this.send(message);
  }

  action(action: GameAction): void {
    if (this.currentMatch < 0) return;
    const message = emptyMessage('action');
    message.matchId = this.currentMatch;
    message.sequence = this.sequence++;
    message.action = action;
    this.send(message);
  }

  leave(): void {
    const message = emptyMessage('leave');
    this.send(message);
    this.close();
  }

  private send(message: RoomMessage): void {
    if (!this.secure || this.closed) return;
    try {
      this.secure.send(JSON.stringify(message));
    } catch (error) {
      this.finish(error instanceof Error ? error.message : String(error));
    }
  }

  close(): void {
    if (this.closed) return;
    this.closed = true;
    this.secure?.close();
  }

  private finish(reason: string): void {
    if (this.closed) return;
    this.closed = true;
    this.events.onClosed(reason);
  }
}

export interface HostEvents extends RoomEvents {
  onMembersChanged(snapshot: RoomSnapshot): void;
}

interface GuestEntry {
  secure: SecureLink;
  name: string | null;
}

interface BotTask {
  seat: number;
  running: boolean;
}

/** 网页房主：唯一权威，负责计时、AI 与按接收者裁剪的广播。 */
export class HostSession {
  private readonly room: RoomApi;
  private readonly memberId = newId();
  private readonly guests = new Map<string, GuestEntry>();
  private readonly botTasks = new Map<number, BotTask>();
  private engineApi: EngineApi | null = null;
  private tickTimer: ReturnType<typeof setInterval> | null = null;
  private closed = false;
  private difficulty = 1;
  private realtime = false;
  private matchId = 0;

  constructor(
    readonly roomId: string,
    gameId: string,
    capacity: number,
    private readonly password: string,
    private readonly hostName: string,
    private readonly events: HostEvents,
  ) {
    this.room = new RoomApi(roomId, gameId, capacity);
    this.room.addMember(this.memberId, hostName, true);
  }

  snapshot(): RoomSnapshot { return this.room.snapshot(this.memberId); }

  private broadcast(message?: string): void {
    const current = this.snapshot();
    this.matchId = current.matchId;
    for (const [id, entry] of this.guests) {
      if (!current.members.some((member) => member.id === id)) continue;
      const room = this.room.snapshot(id);
      if (message) room.message = message;
      const envelope = emptyMessage('room');
      envelope.room = room;
      try { entry.secure.send(JSON.stringify(envelope)); } catch { /* 连接销毁走 onClosed */ }
    }
    this.events.onRoom(current);
    this.events.onMembersChanged(current);
  }

  private readonly rawLinks = new Map<string, SecureLink>();

  /** UI 在原始连接打开时调用；返回的安全链路需由 UI 转发握手期消息。 */
  registerRaw(raw: Link): SecureLink {
    const secure = SecureLink.host(raw, {
      onReady: (link) => { this.guests.set(link.id, { secure: link, name: null }); this.events.onStatus('有玩家正在验证口令'); },
      onMessage: (link, text) => this.handleGuest(link.id, text),
      onClosed: (link, reason) => { this.rawLinks.delete(raw.id); this.dropGuest(link.id, reason); },
    }, this.roomId, this.password);
    this.rawLinks.set(raw.id, secure);
    return secure;
  }

  handleRawMessage(rawId: string, text: string): void {
    this.rawLinks.get(rawId)?.handleRawMessage(text);
  }

  rawClosed(rawId: string, reason: string | null): void {
    this.rawLinks.get(rawId)?.rawClosed(reason);
    this.rawLinks.delete(rawId);
  }

  private handleGuest(linkId: string, text: string): void {
    const entry = this.guests.get(linkId);
    if (!entry || this.closed) return;
    let message: RoomMessage;
    try {
      message = parseRoomMessage(text);
    } catch {
      this.reject(entry, '消息格式无效');
      return;
    }
    switch (message.type) {
      case 'join': {
        if (entry.name !== null) return;
        const name = message.name.trim().slice(0, 20);
        if (!name) { this.reject(entry, '昵称无效'); return; }
        if (!this.room.addMember(linkId, name, false)) { this.reject(entry, '房间已满或对局已开始'); return; }
        entry.name = name;
        this.events.onStatus(`${name} 已加入`);
        this.broadcast();
        break;
      }
      case 'ready': {
        if (entry.name === null) return;
        this.room.setReady(linkId, message.ready);
        this.broadcast();
        break;
      }
      case 'action': {
        if (entry.name === null || !message.action) return;
        const applied = this.room.apply(linkId, message.matchId, message.sequence, message.action);
        if (applied) this.broadcast();
        else this.resync(entry, '操作无效或已过期，已同步最新状态');
        break;
      }
      case 'leave': {
        this.dropGuest(linkId, `${entry.name ?? '玩家'} 离开了房间`);
        break;
      }
      default:
        break;
    }
  }

  private reject(entry: GuestEntry, reason: string): void {
    const message = emptyMessage('reject');
    message.message = reason;
    try { entry.secure.send(JSON.stringify(message)); } catch { /* ignore */ }
    entry.secure.close();
    this.guests.delete(entry.secure.id);
  }

  private resync(entry: GuestEntry, reason: string): void {
    const room = this.room.snapshot(entry.secure.id);
    room.message = reason;
    const envelope = emptyMessage('room');
    envelope.room = room;
    try { entry.secure.send(JSON.stringify(envelope)); } catch { /* ignore */ }
  }

  private dropGuest(linkId: string, reason: string): void {
    this.guests.delete(linkId);
    this.room.disconnect(linkId);
    this.events.onStatus(reason || '一名玩家断开了连接');
    this.broadcast();
  }

  chooseGame(gameId: string, capacity: number): boolean {
    const ok = this.room.chooseGame(this.memberId, gameId, capacity);
    if (ok) this.broadcast();
    return ok;
  }

  setHostReady(ready: boolean): boolean {
    const ok = this.room.setReady(this.memberId, ready);
    if (ok) this.broadcast();
    return ok;
  }

  start(seed: number, difficulty: number, options: Record<string, string> = {}): boolean {
    if (this.engineApi) return false;
    const snapshot = this.snapshot();
    const info = findGame(snapshot.gameId);
    this.difficulty = difficulty;
    this.realtime = info.realtime;
    const engine = new EngineApi(snapshot.gameId, snapshot.capacity, seed, difficulty, options);
    if (!this.room.start(this.memberId, engine.handle)) {
      release(engine.handle);
      return false;
    }
    this.engineApi = engine;
    this.matchId = this.snapshot().matchId;
    this.startLoops();
    this.broadcast();
    return true;
  }

  private startLoops(): void {
    if (this.tickTimer) return;
    this.tickTimer = setInterval(() => {
      if (this.closed || !this.engineApi) return;
      this.room.tick(100);
      this.runBots();
      if (this.realtime) this.broadcast();
    }, 100);
  }

  private runBots(): void {
    const engine = this.engineApi;
    if (!engine) return;
    const snapshot = this.snapshot();
    const view = snapshot.game;
    if (!view) return;
    const webBotCapable = findGame(snapshot.gameId).webBot;
    const seats = this.room.botSeats();
    for (const seat of seats) {
      if (view.winner !== null || view.phase === 'finished') continue;
      const isTurn = view.turn === seat || (view.turn < 0 && this.realtime);
      if (!isTurn) continue;
      let task = this.botTasks.get(seat);
      if (!task) { task = { seat, running: false }; this.botTasks.set(seat, task); }
      if (task.running) continue;
      if (!webBotCapable && this.requiresExternalEngine(snapshot.gameId)) continue;
      task.running = true;
      void this.botMove(engine, seat, view).finally(() => { task!.running = false; });
    }
  }

  private requiresExternalEngine(gameId: string): boolean {
    return gameId === 'go' || gameId === 'chess';
  }

  private async botMove(engine: EngineApi, seat: number, view: import('../net/types').GameSnapshot): Promise<void> {
    try {
      let action: GameAction | null = null;
      if (view.gameId === 'chess') {
        const uci = await chessBestMove(view.extra.fen ?? '', this.difficulty);
        action = uciToAction(uci, view.legal);
      } else {
        action = engine.botAction(seat, this.difficulty);
      }
      if (action && !this.closed) {
        this.room.applyBot(seat, this.matchId, action);
        this.broadcast();
      }
    } catch {
      /* 引擎失败则该席位暂停，不阻塞对局 */
    }
  }

  applyHostAction(action: GameAction): boolean {
    const applied = this.room.apply(this.memberId, this.matchId, this.sequence++, action);
    if (applied) this.broadcast();
    return applied;
  }

  private sequence = 0;

  returnToLobby(): boolean {
    if (!this.room.returnToLobby(this.memberId)) return false;
    if (this.tickTimer) { clearInterval(this.tickTimer); this.tickTimer = null; }
    this.botTasks.clear();
    if (this.engineApi) { release(this.engineApi.handle); this.engineApi = null; }
    this.sequence = 0;
    this.broadcast();
    return true;
  }

  close(): void {
    if (this.closed) return;
    this.closed = true;
    if (this.tickTimer) { clearInterval(this.tickTimer); this.tickTimer = null; }
    if (this.engineApi) { release(this.engineApi.handle); this.engineApi = null; }
    for (const entry of this.guests.values()) entry.secure.close();
    this.guests.clear();
  }
}

export { PROTOCOL_VERSION };
