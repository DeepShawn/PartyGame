/** 与 Kotlin 共享核心逐字段一致的线上协议；不要添加未知字段（解码器会拒绝）。 */
export interface GameAction { type: string; values: number[]; text: string }
export interface GameEntity {
  id: number; kind: string; owner: number; x: number; y: number;
  width: number; height: number; value: number; angle: number;
}
export interface GameSnapshot {
  gameId: string; players: number; turn: number; phase: string;
  width: number; height: number; cells: number[]; entities: GameEntity[];
  scores: number[]; hand: number[]; counts: number[]; legal: GameAction[];
  labels: string[]; extra: Record<string, string>; message: string;
  winner: number | null; tick: number;
}
export interface RoomMember {
  id: string; name: string; seat: number; host: boolean; ready: boolean; connected: boolean;
}
export interface RoomSnapshot {
  roomId: string; gameId: string; capacity: number; members: RoomMember[];
  phase: string; matchId: number; yourSeat: number; bots: number[]; game: GameSnapshot | null; message: string;
}
export interface RoomMessage {
  type: string; protocolVersion: number; roomId: string; matchId: number; sequence: number;
  name: string; gameId: string; ready: boolean;
  action: GameAction | null; room: RoomSnapshot | null; message: string;
}
export interface GameInfo {
  id: string; title: string; subtitle: string; category: string; rules: string;
  minPlayers: number; maxPlayers: number; realtime: boolean; bot: boolean; webBot: boolean;
}

export interface AuthChallenge { type: 'challenge'; roomId: string; sid: string; salt: string; B: string }
export interface ClientProof { type: 'proof'; sid: string; A: string; M1: string }
export interface ServerProof { type: 'verified'; sid: string; M2: string }

export const PROTOCOL_VERSION = 1;

export function emptyMessage(type: string): RoomMessage {
  return {
    type,
    protocolVersion: PROTOCOL_VERSION,
    roomId: '',
    matchId: 0,
    sequence: 0,
    name: '',
    gameId: '',
    ready: false,
    action: null,
    room: null,
    message: '',
  };
}

export function action(type: string, values: number[] = [], text = ''): GameAction {
  return { type, values, text };
}

export function parseRoomMessage(raw: string): RoomMessage {
  const value = JSON.parse(raw) as Partial<RoomMessage>;
  if (typeof value !== 'object' || value === null || typeof value.type !== 'string') throw new Error('消息格式无效');
  const message = emptyMessage(value.type);
  if (typeof value.protocolVersion === 'number') message.protocolVersion = value.protocolVersion;
  if (typeof value.roomId === 'string') message.roomId = value.roomId;
  if (typeof value.matchId === 'number') message.matchId = value.matchId;
  if (typeof value.sequence === 'number') message.sequence = value.sequence;
  if (typeof value.name === 'string') message.name = value.name;
  if (typeof value.gameId === 'string') message.gameId = value.gameId;
  if (typeof value.ready === 'boolean') message.ready = value.ready;
  if (value.action && typeof value.action === 'object') {
    const a = value.action as Partial<GameAction>;
    if (typeof a.type === 'string') {
      message.action = {
        type: a.type,
        values: Array.isArray(a.values) ? a.values.filter((n): n is number => typeof n === 'number') : [],
        text: typeof a.text === 'string' ? a.text : '',
      };
    }
  }
  if (value.room && typeof value.room === 'object') message.room = value.room as RoomSnapshot;
  if (typeof value.message === 'string') message.message = value.message;
  return message;
}
