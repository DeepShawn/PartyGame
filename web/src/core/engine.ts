// @ts-ignore Kotlin/JS 产物由构建脚本生成（KMP 共享规则编译结果）
import * as partyModule from './generated/partycore.mjs';
import type { GameAction, GameInfo, GameSnapshot, RoomSnapshot } from '../net/types';

interface PartyCoreApi {
  protocolVersion(): number;
  catalog(): string;
  newEngine(gameId: string, players: number, seed: number, difficulty: number, optionsJson: string): number;
  apply(engine: number, seat: number, actionJson: string): boolean;
  snapshot(engine: number, viewer: number): string;
  tick(engine: number, elapsedMs: number): void;
  botAction(engine: number, seat: number, difficulty: number): string;
  newRoom(roomId: string, gameId: string, capacity: number): number;
  roomAddMember(room: number, id: string, name: string, host: boolean): boolean;
  roomSetReady(room: number, id: string, ready: boolean): boolean;
  roomChooseGame(room: number, id: string, gameId: string, players: number): boolean;
  roomStart(room: number, memberId: string, engine: number): boolean;
  roomApply(room: number, id: string, matchId: number, sequence: number, actionJson: string): boolean;
  roomApplyBot(room: number, seat: number, matchId: number, actionJson: string): boolean;
  roomBotSeats(room: number): string;
  roomSnapshot(room: number, id: string): string;
  roomDisconnect(room: number, id: string): void;
  roomReconnect(room: number, id: string): boolean;
  roomTick(room: number, elapsedMs: number): void;
  roomReturnToLobby(room: number, id: string): boolean;
  release(handle: number): void;
}

const partyCore = (partyModule as unknown as { PartyCore: PartyCoreApi & { getInstance?: () => PartyCoreApi } }).PartyCore;
// Kotlin/JS 把 object 导出为类，需通过 getInstance() 取单例（无该函数时直接当作实例）。
const core = typeof partyCore.getInstance === 'function' ? partyCore.getInstance() : partyCore;
if (!core) throw new Error('共享核心未加载：请先构建 shared 模块');

export const protocolVersion = core.protocolVersion();

export const gameCatalog: GameInfo[] = JSON.parse(core.catalog()) as GameInfo[];

export function findGame(id: string): GameInfo {
  const info = gameCatalog.find((game) => game.id === id);
  if (!info) throw new Error(`未知游戏: ${id}`);
  return info;
}

/** 房间/引擎句柄封装：跨 JS 边界只传 JSON 字符串。 */
export class RoomApi {
  readonly handle: number;

  constructor(roomId: string, gameId: string, capacity: number) {
    const handle = core.newRoom(roomId, gameId, capacity);
    if (handle < 0) throw new Error('无法创建房间');
    this.handle = handle;
  }

  addMember(id: string, name: string, host: boolean): boolean {
    return core.roomAddMember(this.handle, id, name, host);
  }
  setReady(id: string, ready: boolean): boolean {
    return core.roomSetReady(this.handle, id, ready);
  }
  chooseGame(id: string, gameId: string, players: number): boolean {
    return core.roomChooseGame(this.handle, id, gameId, players);
  }
  start(memberId: string, engine: number): boolean {
    return core.roomStart(this.handle, memberId, engine);
  }
  apply(id: string, matchId: number, sequence: number, action: GameAction): boolean {
    return core.roomApply(this.handle, id, matchId, sequence, JSON.stringify(action));
  }
  applyBot(seat: number, matchId: number, action: GameAction): boolean {
    return core.roomApplyBot(this.handle, seat, matchId, JSON.stringify(action));
  }
  botSeats(): number[] {
    return JSON.parse(core.roomBotSeats(this.handle)) as number[];
  }
  snapshot(memberId: string): RoomSnapshot {
    return JSON.parse(core.roomSnapshot(this.handle, memberId)) as RoomSnapshot;
  }
  disconnect(id: string): void { core.roomDisconnect(this.handle, id); }
  reconnect(id: string): boolean { return core.roomReconnect(this.handle, id); }
  tick(elapsedMs: number): void { core.roomTick(this.handle, elapsedMs); }
  returnToLobby(id: string): boolean { return core.roomReturnToLobby(this.handle, id); }
}

export class EngineApi {
  readonly handle: number;

  constructor(gameId: string, players: number, seed: number, difficulty: number, options: Record<string, string> = {}) {
    const handle = core.newEngine(gameId, players, seed, difficulty, JSON.stringify(options));
    if (handle < 0) throw new Error('无法创建游戏');
    this.handle = handle;
  }

  apply(seat: number, action: GameAction): boolean {
    return core.apply(this.handle, seat, JSON.stringify(action));
  }
  snapshot(viewer: number): GameSnapshot {
    return JSON.parse(core.snapshot(this.handle, viewer)) as GameSnapshot;
  }
  tick(elapsedMs: number): void { core.tick(this.handle, elapsedMs); }
  botAction(seat: number, difficulty: number): GameAction | null {
    const text = core.botAction(this.handle, seat, difficulty);
    return text ? (JSON.parse(text) as GameAction) : null;
  }
}

export function release(handle: number): void {
  core.release(handle);
}
