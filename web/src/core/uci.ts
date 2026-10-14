/** 国际象棋 UCI 着法与共享核心动作格式的纯转换逻辑（无运行时依赖，可独立测试）。 */
import type { GameAction } from '../net/types';

/**
 * UCI 方格 → 核心 8×8 索引。核心第 0 行是第 8 横排（黑方底线，index 0 = a8），
 * 与 ChessGame.square() / 棋盘 canvas 的自上而下行序一致，因此这里必须做横排翻转。
 * 例：'a8' → 0，'e2' → 52。
 */
export function squareIndex(square: string): number {
  const file = square.charCodeAt(0) - 'a'.charCodeAt(0);
  const rank = 7 - (square.charCodeAt(1) - '1'.charCodeAt(0));
  return rank * 8 + file;
}

const PROMOTION_CODE: Record<string, number> = { q: 5, r: 4, b: 3, n: 2 };

export function uciToAction(uci: string, legal: GameAction[]): GameAction | null {
  const match = /^([a-h][1-8])([a-h][1-8])([qrbn])?$/.exec(uci);
  if (!match) return null;
  const from = squareIndex(match[1]);
  const to = squareIndex(match[2]);
  const promotion = match[3] ? PROMOTION_CODE[match[3]] : 0;
  const candidates = legal.filter(
    (action) => action.type === 'move' && action.values[0] === from && action.values[1] === to,
  );
  if (candidates.length === 0) return null;
  if (promotion) return candidates.find((action) => action.values[2] === promotion) ?? null;
  return candidates.find((action) => action.values.length === 2) ?? candidates[0];
}
