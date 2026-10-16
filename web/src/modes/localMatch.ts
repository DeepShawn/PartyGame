/** 本机同屏对局：直接由 EngineApi 驱动，不联网；AI 席位在本地按时限自动行动。 */
import { EngineApi, findGame, release } from '../core/engine';
import { chessBestMove } from '../core/stockfish';
import { uciToAction } from '../core/uci';
import type { GameAction, GameSnapshot } from '../net/types';
import { isRealtime } from '../render/input';

export interface LocalMatchOptions {
  gameId: string;
  players: number;
  difficulty: number;
  bots: number[];
  options?: Record<string, string>;
}

const TICK_MS = 100;
const BOT_THINK_MS = 420;

export class LocalMatch {
  readonly gameId: string;
  readonly players: number;
  readonly difficulty: number;
  readonly bots: Set<number>;
  private readonly engine: EngineApi;
  private readonly listeners = new Set<(snapshot: GameSnapshot) => void>();
  private readonly running = new Set<number>();
  private timer: ReturnType<typeof setInterval> | null = null;
  private closed = false;
  private lastTurn = -2;
  private turnReadyAt = 0;
  private currentViewer = 0;

  constructor(options: LocalMatchOptions) {
    const bots = options.bots.filter((seat) => seat >= 0 && seat < options.players);
    this.gameId = options.gameId;
    this.players = options.players;
    this.difficulty = options.difficulty;
    this.bots = new Set(bots);
    this.engine = new EngineApi(options.gameId, options.players, Math.floor(Math.random() * 2_147_483_647), options.difficulty, options.options ?? {});
  }

  subscribe(listener: (snapshot: GameSnapshot) => void): () => void {
    this.listeners.add(listener);
    const snapshot = this.read();
    if (snapshot) listener(snapshot);
    return () => {
      this.listeners.delete(listener);
    };
  }

  /** 与安卓端 preferredViewer 一致：多名真人时跟随当前回合的真人，否则固定第一名真人。 */
  private humanSeats(): number[] {
    return Array.from({ length: this.players }, (_, seat) => seat).filter((seat) => !this.bots.has(seat));
  }

  viewer(): number {
    return this.currentViewer;
  }

  snapshot(): GameSnapshot | null {
    if (this.closed) return null;
    try {
      const probe = this.engine.snapshot(0);
      const humans = this.humanSeats();
      const viewer = humans.length > 1 && humans.includes(probe.turn) ? probe.turn : humans[0] ?? 0;
      this.currentViewer = viewer;
      return this.engine.snapshot(viewer);
    } catch {
      return null;
    }
  }

  apply(seat: number, action: GameAction): boolean {
    if (this.closed) return false;
    try {
      const applied = this.engine.apply(seat, action);
      if (applied) this.publish();
      return applied;
    } catch {
      return false;
    }
  }

  start(): void {
    if (this.timer || this.closed) return;
    this.timer = setInterval(() => this.step(), TICK_MS);
    this.publish();
  }

  dispose(): void {
    if (this.closed) return;
    this.closed = true;
    if (this.timer) {
      clearInterval(this.timer);
      this.timer = null;
    }
    this.running.clear();
    this.listeners.clear();
    try {
      release(this.engine.handle);
    } catch {
      /* 句柄已释放 */
    }
  }

  private read(): GameSnapshot | null {
    return this.snapshot();
  }

  private publish(): void {
    const snapshot = this.read();
    if (!snapshot) return;
    for (const listener of this.listeners) listener(snapshot);
  }

  private step(): void {
    if (this.closed) return;
    try {
      this.engine.tick(TICK_MS);
    } catch {
      /* 引擎已结束 */
    }
    this.runBots();
    this.publish();
  }

  private runBots(): void {
    if (this.bots.size === 0) return;
    let probe: GameSnapshot;
    try {
      probe = this.engine.snapshot(0);
    } catch {
      return;
    }
    if (probe.winner !== null || probe.phase === 'finished') return;
    const now = Date.now();
    if (probe.turn !== this.lastTurn) {
      this.lastTurn = probe.turn;
      this.turnReadyAt = now + BOT_THINK_MS;
    }
    for (const seat of this.bots) {
      if (this.closed) return;
      if (this.running.has(seat)) continue;
      const isTurn = probe.turn === seat || (probe.turn < 0 && isRealtime(this.gameId));
      if (!isTurn) continue;
      if (probe.turn >= 0 && now < this.turnReadyAt) continue;
      this.running.add(seat);
      void this.botMove(seat).finally(() => this.running.delete(seat));
    }
  }

  private async botMove(seat: number): Promise<void> {
    try {
      const view = this.engine.snapshot(seat);
      if (view.winner !== null || view.phase === 'finished' || (view.turn >= 0 && view.turn !== seat)) return;
      let action: GameAction | null = null;
      if (this.gameId === 'chess') {
        const uci = await chessBestMove(view.extra['fen'] ?? '', this.difficulty);
        action = uciToAction(uci, view.legal);
      } else {
        action = this.engine.botAction(seat, this.difficulty);
      }
      action = action ?? view.legal[0] ?? null;
      if (action && !this.closed) {
        this.engine.apply(seat, action);
        this.publish();
      }
    } catch {
      /* 该席位暂停思考，不阻塞对局 */
    }
  }
}
