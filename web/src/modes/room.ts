/** 房间会话抽象：公网房主 / 公网访客 / 局域网访客共用同一订阅接口。 */
import type { GameAction, RoomSnapshot } from '../net/types';

export interface RoomState {
  snapshot: RoomSnapshot | null;
  status: string;
  closed: string | null;
}

export type RoomListener = (state: RoomState) => void;

export abstract class RoomSession {
  protected state: RoomState = { snapshot: null, status: '', closed: null };
  private readonly listeners = new Set<RoomListener>();

  subscribe(listener: RoomListener): () => void {
    this.listeners.add(listener);
    listener(this.state);
    return () => {
      this.listeners.delete(listener);
    };
  }

  protected emit(patch: Partial<RoomState>): void {
    this.state = { ...this.state, ...patch };
    for (const listener of this.listeners) listener(this.state);
  }

  /** 清空瞬时状态提示（例如“已加入房间”），避免重复展示。 */
  clearStatus(): void {
    if (this.state.status) this.emit({ status: '' });
  }

  abstract leave(): void;
}
