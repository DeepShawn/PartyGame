/** 输入语义与安卓端 GameInput 保持一致：本机同屏与联机共用同一套判定。 */
import type { GameAction, GameSnapshot } from '../net/types';

export const ARCADE_GAMES = ['reaction', 'color', 'math', 'memory', 'snake', 'tank', 'bomber', 'dodge'];
const QUICK_TOUCH_GAMES = ['coinflip', 'numberguess', 'oddone', 'targettap', 'pattern', 'sumten', 'highlow', 'color_match', 'memoryrush', 'speedchoice'];

export function isRealtime(gameId: string): boolean {
  return gameId === 'reaction' || gameId === 'color' || gameId === 'math'
    || gameId === 'snake' || gameId === 'tank' || gameId === 'bomber' || gameId === 'dodge'
    || QUICK_TOUCH_GAMES.includes(gameId);
}

export function canAct(snapshot: GameSnapshot | null, localSeats: number[], viewer: number, seat: number): boolean {
  if (!snapshot) return false;
  if (snapshot.winner !== null || snapshot.phase === 'finished') return false;
  if (!localSeats.includes(seat) || seat < 0 || seat >= snapshot.players) return false;
  return snapshot.turn < 0 || (viewer === seat && (seat === snapshot.turn || snapshot.legal.length > 0));
}

export function movesAt(legal: GameAction[], path: number[]): GameAction[] {
  return legal.filter((action) => action.type === 'move'
    && action.values.length >= path.length
    && path.every((value, index) => action.values[index] === value));
}

export function nextTargets(legal: GameAction[], path: number[]): Set<number> {
  const targets = new Set<number>();
  for (const action of movesAt(legal, path)) {
    const value = action.values[path.length];
    if (typeof value === 'number') targets.add(value);
  }
  return targets;
}

/** 街机类操作校验：席位等于当前操作者时以合法列表为准，其他本机席位按游戏规则核对。 */
export function arcadeAllowed(gameId: string, snapshot: GameSnapshot, seat: number, viewer: number, action: GameAction): boolean {
  if (!actionAllowSeat(snapshot, seat)) return false;
  if (snapshot.extra['alive']?.split(',')[seat] === '0') return false;
  if (seat === viewer) return snapshot.legal.some((entry) => sameAction(entry, action));
  return remoteArcadeAllowed(gameId, snapshot, seat, action);
}

function actionAllowSeat(snapshot: GameSnapshot, seat: number): boolean {
  return snapshot.winner === null && snapshot.phase !== 'finished' && seat >= 0 && seat < snapshot.players;
}

function remoteArcadeAllowed(gameId: string, snapshot: GameSnapshot, seat: number, action: GameAction): boolean {
  const single = action.values.length === 1 ? action.values[0] : Number.NaN;
  switch (gameId) {
    case 'reaction':
      return action.type === 'tap' && action.values.length === 0
        && (snapshot.phase === 'waiting' || snapshot.phase === 'signal')
        && snapshot.extra['responses']?.split(',')[seat] === '-1';
    case 'color':
    case 'math':
      return action.type === 'answer' && single >= 0 && single <= 3
        && snapshot.phase === 'playing'
        && snapshot.extra['answered']?.split(',')[seat] === '0';
    case 'coinflip': case 'numberguess': case 'oddone': case 'targettap': case 'pattern': case 'sumten': case 'highlow': case 'color_match': case 'memoryrush': case 'speedchoice':
      return action.type === 'choose' && single >= 0 && single <= 3 && snapshot.phase === 'playing';
    case 'snake':
    case 'dodge':
      return action.type === 'direction' && single >= -1 && single <= 3;
    case 'tank':
      return (action.type === 'direction' && single >= -1 && single <= 3) || (action.type === 'fire' && action.values.length === 0);
    case 'bomber':
      return (action.type === 'direction' && single >= -1 && single <= 3) || (action.type === 'bomb' && action.values.length === 0);
    default:
      return false;
  }
}

export function sameAction(left: GameAction, right: GameAction): boolean {
  return left.type === right.type
    && left.text === right.text
    && left.values.length === right.values.length
    && left.values.every((value, index) => value === right.values[index]);
}

export function squareLabel(index: number, width: number): string {
  if (!Number.isFinite(index) || index < 0 || width <= 0) return String(index + 1);
  const column = index % width;
  const letter = column < 26 ? String.fromCharCode('A'.charCodeAt(0) + column) : String(column + 1);
  return `${letter}${Math.floor(index / width) + 1}`;
}

export function promotionLabel(code: number): string {
  return ({ 2: '马', 3: '象', 4: '车', 5: '后' } as Record<number, string>)[code] ?? String(code);
}

const SUITS = ['♠', '♥', '♦', '♣'];

export function cardLabel(id: number): string {
  if (id === 52) return '小王';
  if (id === 53) return '大王';
  const rank = Math.floor(id / 4) + 3;
  const label = rank === 11 ? 'J' : rank === 12 ? 'Q' : rank === 13 ? 'K' : rank === 14 ? 'A' : rank === 15 ? '2' : String(rank);
  return `${SUITS[id % 4]}${label}`;
}

export function cardIsRed(id: number): boolean {
  return id === 53 || (id < 52 && (id % 4 === 1 || id % 4 === 2));
}

export function actionLabel(gameId: string, action: GameAction, width: number): string {
  switch (action.type) {
    case 'resign': return '认输';
    case 'pass': return gameId === 'doudizhu' ? '不出' : '停一手';
    case 'accept_score': return '确认数目';
    case 'resume': return '继续行棋';
    case 'hint': return '提示';
    case 'bid': return action.values[0] === 0 ? '不叫' : `叫 ${action.values[0]} 分`;
    case 'dead': return `标记死子 ${action.values.map((value) => squareLabel(value, width)).join(' ')}`;
    case 'place':
    case 'flip':
      return `${action.type === 'flip' ? '翻牌' : '落子'} ${action.values.map((value) => squareLabel(value, width)).join(' ')}`;
    case 'move': {
      const cells = gameId === 'chess' ? action.values.slice(0, 2) : action.values;
      const path = cells.map((value) => squareLabel(value, width)).join(' → ');
      return action.values.length === 3 ? `${path} 升${promotionLabel(action.values[2])}` : path;
    }
    case 'direction': {
      const value = action.values[0];
      return value === -1 ? '停止' : `转向 ${['上', '右', '下', '左'][value] ?? value}`;
    }
    case 'tap': return '抢按';
    case 'answer': return `选项 ${(action.values[0] ?? 0) + 1}`;
    case 'fire': return '开火';
    case 'bomb': return '炸弹';
    case 'play': return `出牌 ${action.values.map(cardLabel).join(' ')}`;
    default: return action.type;
  }
}
