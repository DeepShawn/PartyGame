/** 统一 canvas2D 渲染：交叉点棋盘（围棋/五子棋/象棋）、格子棋盘、记忆翻牌与街机竞技场。 */
import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react';
import type { GameSnapshot } from '../net/types';
import type { Palette } from '../app/theme';

const CROSS_GAMES = new Set(['go', 'gomoku', 'xiangqi']);
const ARENA_GAMES = new Set(['snake', 'tank', 'bomber', 'dodge']);
const AMBER = '#CC740D';

const PLAYER_COLORS = ['#207BAE', '#B74356', '#896216'];
const CHESS_WHITE = ['', '♙', '♘', '♗', '♖', '♕', '♔'];
const CHESS_BLACK = ['', '♟', '♞', '♝', '♜', '♛', '♚'];
const XIANGQI_RED = ['', '兵', '馬', '相', '俥', '炮', '仕', '帥'];
const XIANGQI_BLACK = ['', '卒', '馬', '象', '車', '砲', '士', '將'];

interface Geometry {
  cross: boolean;
  left: number;
  top: number;
  step: number;
  gridWidth: number;
  gridHeight: number;
  edge: number;
}

interface Props {
  snapshot: GameSnapshot;
  palette: Palette;
  coordinates: boolean;
  highlights: Set<number>;
  selected: number[];
  onCell: (index: number) => void;
}

function geometryFor(snapshot: GameSnapshot, width: number, height: number, coordinates: boolean): Geometry {
  const cross = CROSS_GAMES.has(snapshot.gameId);
  const margin = coordinates ? 26 : 15;
  const cols = cross ? Math.max(1, snapshot.width - 1) : Math.max(1, snapshot.width);
  const rows = cross ? Math.max(1, snapshot.height - 1) : Math.max(1, snapshot.height);
  const padding = cross ? 1 : 0;
  const step = Math.max(1, Math.min((width - 2 * margin) / (cols + padding), (height - 2 * margin) / (rows + padding)));
  const gridWidth = cols * step;
  const gridHeight = rows * step;
  return {
    cross,
    left: (width - gridWidth) / 2,
    top: (height - gridHeight) / 2,
    step,
    gridWidth,
    gridHeight,
    edge: cross ? step * 0.5 : 0,
  };
}

function cssColor(value: string, fallback = '#000000'): string {
  return /^#[0-9a-fA-F]{3,8}$/.test(value) ? value : fallback;
}

let roundRectReady = false;
/** 旧版 WebView 没有 CanvasRenderingContext2D.roundRect，补一个等价实现。 */
function ensureRoundRect(context: CanvasRenderingContext2D): void {
  if (roundRectReady) return;
  roundRectReady = true;
  if (typeof (context as { roundRect?: unknown }).roundRect === 'function') return;
  const proto = Object.getPrototypeOf(context) as CanvasRenderingContext2D;
  proto.roundRect = function (this: CanvasRenderingContext2D, x: number, y: number, w: number, h: number, radii?: number | DOMPointInit | (number | DOMPointInit)[]): void {
    const raw = typeof radii === 'number' ? radii : Array.isArray(radii) && typeof radii[0] === 'number' ? radii[0] : 0;
    const radius = Math.max(0, Math.min(raw, Math.abs(w) / 2, Math.abs(h) / 2));
    this.moveTo(x + radius, y);
    this.lineTo(x + w - radius, y);
    this.arcTo(x + w, y, x + w, y + radius, radius);
    this.lineTo(x + w, y + h - radius);
    this.arcTo(x + w, y + h, x + w - radius, y + h, radius);
    this.lineTo(x + radius, y + h);
    this.arcTo(x, y + h, x, y + h - radius, radius);
    this.lineTo(x, y + radius);
    this.arcTo(x, y, x + radius, y, radius);
  } as typeof proto.roundRect;
}

function withAlpha(hex: string, alpha: number): string {
  const value = hex.replace('#', '');
  const full = value.length === 3 ? value.split('').map((c) => c + c).join('') : value;
  const r = parseInt(full.slice(0, 2), 16);
  const g = parseInt(full.slice(2, 4), 16);
  const b = parseInt(full.slice(4, 6), 16);
  return `rgba(${r}, ${g}, ${b}, ${alpha})`;
}

function gtpIndex(token: string, width: number, height: number): number {
  const match = /^([A-HJ-Ta-hj-t])(\d{1,2})$/.exec(token.trim());
  if (!match) return -1;
  const letter = match[1].toUpperCase();
  let column = letter.charCodeAt(0) - 'A'.charCodeAt(0);
  if (column > 8) column -= 1;
  const row = Number(match[2]);
  if (column < 0 || column >= width || row < 1 || row > height) return -1;
  return (height - row) * width + column;
}

function parseFenBoard(fen: string): number[] | null {
  const field = fen.trim().split(/\s+/)[0];
  if (!field) return null;
  const board = new Array<number>(64).fill(0);
  const rows = field.split('/');
  if (rows.length !== 8) return null;
  for (let y = 0; y < 8; y += 1) {
    let x = 0;
    for (const ch of rows[y]) {
      if (ch >= '1' && ch <= '8') {
        x += Number(ch);
      } else {
        const piece = ' pnbrqk'.indexOf(ch.toLowerCase());
        if (piece < 1 || x >= 8) return null;
        board[y * 8 + x] = ch === ch.toUpperCase() ? piece : -piece;
        x += 1;
      }
    }
    if (x !== 8) return null;
  }
  return board;
}

export function BoardCanvas({ snapshot, palette, coordinates, highlights, selected, onCell }: Props) {
  const wrapRef = useRef<HTMLDivElement>(null);
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const pressedRef = useRef(-1);
  const [size, setSize] = useState({ width: 0, height: 0 });

  useLayoutEffect(() => {
    const wrap = wrapRef.current;
    if (!wrap) return;
    const observer = new ResizeObserver((entries) => {
      const rect = entries[0]?.contentRect;
      if (!rect) return;
      setSize({ width: Math.max(0, Math.floor(rect.width)), height: Math.max(0, Math.floor(rect.height)) });
    });
    observer.observe(wrap);
    const rect = wrap.getBoundingClientRect();
    setSize({ width: Math.floor(rect.width), height: Math.floor(rect.height) });
    return () => observer.disconnect();
  }, []);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas || size.width <= 0 || size.height <= 0) return;
    const context = canvas.getContext('2d');
    if (!context) return;
    ensureRoundRect(context);
    const dpr = Math.min(window.devicePixelRatio || 1, 3);
    const pixelWidth = Math.round(size.width * dpr);
    const pixelHeight = Math.round(size.height * dpr);
    if (canvas.width !== pixelWidth) canvas.width = pixelWidth;
    if (canvas.height !== pixelHeight) canvas.height = pixelHeight;
    context.setTransform(dpr, 0, 0, dpr, 0, 0);
    context.clearRect(0, 0, size.width, size.height);
    if (snapshot.width <= 0 || snapshot.height <= 0) return;
    const geometry = geometryFor(snapshot, size.width, size.height, coordinates);
    if (ARENA_GAMES.has(snapshot.gameId)) drawArena(context, snapshot, palette, geometry, size.width, size.height, highlights, selected);
    else drawBoard(context, snapshot, palette, geometry, coordinates, highlights, selected);
  }, [snapshot, palette, coordinates, highlights, selected, size]);

  const hitTest = useCallback((clientX: number, clientY: number): number => {
    const canvas = canvasRef.current;
    if (!canvas || snapshot.width <= 0 || snapshot.height <= 0) return -1;
    const rect = canvas.getBoundingClientRect();
    const geometry = geometryFor(snapshot, rect.width, rect.height, coordinates);
    const localX = clientX - rect.left;
    const localY = clientY - rect.top;
    const column = geometry.cross
      ? Math.round((localX - geometry.left) / geometry.step)
      : Math.floor((localX - geometry.left) / geometry.step);
    const row = geometry.cross
      ? Math.round((localY - geometry.top) / geometry.step)
      : Math.floor((localY - geometry.top) / geometry.step);
    if (column < 0 || column >= snapshot.width || row < 0 || row >= snapshot.height) return -1;
    return row * snapshot.width + column;
  }, [snapshot.width, snapshot.height, coordinates]);

  return (
    <div className="board-wrap" ref={wrapRef}>
      <canvas
        ref={canvasRef}
        className="board-canvas"
        style={{ width: size.width ? `${size.width}px` : '100%', height: size.height ? `${size.height}px` : '100%' }}
        aria-label={`${snapshot.width} 列 ${snapshot.height} 行棋盘。${snapshot.message}`}
        role="img"
        onPointerDown={(event) => {
          pressedRef.current = hitTest(event.clientX, event.clientY);
          if (pressedRef.current >= 0) event.currentTarget.setPointerCapture(event.pointerId);
        }}
        onPointerUp={(event) => {
          const cell = hitTest(event.clientX, event.clientY);
          if (cell >= 0 && cell === pressedRef.current) onCell(cell);
          pressedRef.current = -1;
        }}
        onPointerCancel={() => { pressedRef.current = -1; }}
      />
    </div>
  );
}

function drawBoard(
  context: CanvasRenderingContext2D,
  snapshot: GameSnapshot,
  palette: Palette,
  geometry: Geometry,
  coordinates: boolean,
  highlights: Set<number>,
  selected: number[],
): void {
  const { cross, left, top, step, gridWidth, gridHeight, edge } = geometry;
  const light = cssColor(palette.boardLight);
  const dark = cssColor(palette.boardDark);
  const text = cssColor(palette.text);
  const muted = cssColor(palette.muted);
  const accent = cssColor(palette.accent);
  const chequered = snapshot.gameId === 'chess' || snapshot.gameId === 'draughts' || snapshot.gameId === 'reversi';

  context.save();
  if (cross) {
    context.fillStyle = light;
    context.beginPath();
    context.roundRect(left - edge, top - edge, gridWidth + step, gridHeight + step, Math.min(10, step));
    context.fill();
    context.strokeStyle = withAlpha(dark, 0.45);
    context.lineWidth = 1;
    context.stroke();
  }

  const cellWidth = cross ? 0 : step;
  const cellHeight = cross ? 0 : step;
  if (!cross) {
    for (let y = 0; y < snapshot.height; y += 1) {
      for (let x = 0; x < snapshot.width; x += 1) {
        const cx = left + x * cellWidth;
        const cy = top + y * cellHeight;
        if (chequered) {
          context.fillStyle = (x + y) % 2 === 1 ? dark : light;
        } else {
          context.fillStyle = light;
        }
        context.fillRect(cx, cy, cellWidth, cellHeight);
      }
    }
    context.strokeStyle = withAlpha(text, 0.28);
    context.lineWidth = 1;
    for (let x = 0; x <= snapshot.width; x += 1) {
      context.beginPath();
      context.moveTo(left + x * cellWidth, top);
      context.lineTo(left + x * cellWidth, top + snapshot.height * cellHeight);
      context.stroke();
    }
    for (let y = 0; y <= snapshot.height; y += 1) {
      context.beginPath();
      context.moveTo(left, top + y * cellHeight);
      context.lineTo(left + snapshot.width * cellWidth, top + y * cellHeight);
      context.stroke();
    }
  }

  if (cross) {
    context.strokeStyle = withAlpha(text, 0.6);
    context.lineWidth = Math.min(1.4, Math.max(0.7, step * 0.05));
    for (let y = 0; y < snapshot.height; y += 1) {
      context.beginPath();
      context.moveTo(left, top + y * step);
      context.lineTo(left + gridWidth, top + y * step);
      context.stroke();
    }
    const xiangqi = snapshot.gameId === 'xiangqi' && snapshot.width === 9 && snapshot.height === 10;
    for (let x = 0; x < snapshot.width; x += 1) {
      if (xiangqi && x > 0 && x < snapshot.width - 1) {
        context.beginPath();
        context.moveTo(left + x * step, top);
        context.lineTo(left + x * step, top + 4 * step);
        context.stroke();
        context.beginPath();
        context.moveTo(left + x * step, top + 5 * step);
        context.lineTo(left + x * step, top + gridHeight);
        context.stroke();
      } else {
        context.beginPath();
        context.moveTo(left + x * step, top);
        context.lineTo(left + x * step, top + gridHeight);
        context.stroke();
      }
    }
    if (xiangqi) {
      for (const base of [0, 7]) {
        context.beginPath();
        context.moveTo(left + 3 * step, top + base * step);
        context.lineTo(left + 5 * step, top + (base + 2) * step);
        context.moveTo(left + 5 * step, top + base * step);
        context.lineTo(left + 3 * step, top + (base + 2) * step);
        context.stroke();
      }
      context.strokeStyle = withAlpha(text, 0.45);
      for (const y of [3, 6]) {
        for (const x of [0, 2, 4, 6, 8]) markPoint(context, left + x * step, top + y * step, step);
      }
      for (const y of [2, 7]) {
        for (const x of [1, 7]) markPoint(context, left + x * step, top + y * step, step);
      }
      drawLabel(context, '楚 河', left + 2 * step, top + 4.5 * step, step * 0.52, withAlpha(text, 0.55));
      drawLabel(context, '漢 界', left + 6 * step, top + 4.5 * step, step * 0.52, withAlpha(text, 0.55));
    }
  }

  const owners = parseCsv(snapshot.extra['owners']);
  const revealed = new Set(parseCsv(snapshot.extra['revealed']));
  const dead = new Set(parseCsv(snapshot.extra['dead']));
  const chessBoard = snapshot.gameId === 'chess' ? parseFenBoard(snapshot.extra['fen'] ?? '') : null;
  const gtpMoves = snapshot.gameId === 'go'
    ? parseGtpMoves(snapshot.extra['gtpMoves'] ?? '', snapshot.width, snapshot.height)
    : new Map<number, number>();

  for (let y = 0; y < snapshot.height; y += 1) {
    for (let x = 0; x < snapshot.width; x += 1) {
      const index = y * snapshot.width + x;
      const value = snapshot.cells[index] ?? 0;
      const cx = left + (x + (cross ? 0 : 0.5)) * (cross ? step : cellWidth);
      const cy = top + (y + (cross ? 0 : 0.5)) * (cross ? step : cellHeight);
      if (snapshot.gameId === 'memory') {
        drawMemoryCard(context, value, index, cx, cy, step, palette, owners[index] ?? -1, revealed.has(index));
      } else {
        const piece = chessBoard ? (chessBoard[index] ?? 0) : value;
        if (piece !== 0) drawPiece(context, snapshot, piece, cx, cy, step, palette, dead.has(index), gtpMoves.get(index), dark);
      }
      if (highlights.has(index)) {
        context.strokeStyle = accent;
        context.lineWidth = Math.max(2, step * 0.075);
        context.beginPath();
        context.arc(cx, cy, step * 0.3, 0, Math.PI * 2);
        context.stroke();
        context.fillStyle = accent;
        context.beginPath();
        context.arc(cx, cy, step * 0.07, 0, Math.PI * 2);
        context.fill();
      }
      if (selected.includes(index)) {
        context.strokeStyle = AMBER;
        context.lineWidth = Math.max(2, step * 0.085);
        context.beginPath();
        context.roundRect(cx - step * 0.46, cy - step * 0.46, step * 0.92, step * 0.92, Math.min(8, step * 0.12));
        context.stroke();
      }
    }
  }

  if (dead.size > 0) {
    for (const index of dead) {
      const cx = left + (index % snapshot.width) * (cross ? step : cellWidth) + (cross ? 0 : cellWidth * 0.5);
      const cy = top + Math.floor(index / snapshot.width) * (cross ? step : cellHeight) + (cross ? 0 : cellHeight * 0.5);
      context.strokeStyle = cssColor(palette.danger);
      context.lineWidth = Math.max(1.5, step * 0.06);
      context.beginPath();
      context.moveTo(cx - step * 0.2, cy - step * 0.2);
      context.lineTo(cx + step * 0.2, cy + step * 0.2);
      context.moveTo(cx + step * 0.2, cy - step * 0.2);
      context.lineTo(cx - step * 0.2, cy + step * 0.2);
      context.stroke();
    }
  }

  if (coordinates) {
    const size = Math.min(step * 0.4, 13);
    for (let x = 0; x < snapshot.width; x += 1) {
      const label = x < 26 ? String.fromCharCode('A'.charCodeAt(0) + x) : String(x + 1);
      drawLabel(context, label, left + (x + (cross ? 0 : 0.5)) * (cross ? step : cellWidth), top - edge - 10, size, muted);
    }
    for (let y = 0; y < snapshot.height; y += 1) {
      drawLabel(context, String(y + 1), left - edge - 14, top + (y + (cross ? 0 : 0.5)) * (cross ? step : cellHeight), size, muted);
    }
  }
  context.restore();
}

function markPoint(context: CanvasRenderingContext2D, x: number, y: number, step: number): void {
  const gap = step * 0.16;
  const arm = step * 0.14;
  context.beginPath();
  for (const [dx, dy] of [[-1, -1], [1, -1], [-1, 1], [1, 1]] as const) {
    context.moveTo(x + dx * gap, y + dy * gap + dy * arm);
    context.lineTo(x + dx * gap, y + dy * gap);
    context.lineTo(x + dx * gap + dx * arm, y + dy * gap);
  }
  context.stroke();
}

function drawLabel(context: CanvasRenderingContext2D, value: string, x: number, y: number, size: number, color: string): void {
  context.save();
  context.font = `500 ${size}px system-ui, -apple-system, "PingFang SC", "Noto Sans SC", sans-serif`;
  context.textAlign = 'center';
  context.textBaseline = 'middle';
  context.fillStyle = color;
  context.fillText(value, x, y);
  context.restore();
}

function drawPiece(
  context: CanvasRenderingContext2D,
  snapshot: GameSnapshot,
  value: number,
  cx: number,
  cy: number,
  step: number,
  palette: Palette,
  dead: boolean,
  moveNumber: number | undefined,
  darkSquare: string,
): void {
  const gameId = snapshot.gameId;
  context.save();
  switch (gameId) {
    case 'chess': {
      const glyphs = value > 0 ? CHESS_WHITE : CHESS_BLACK;
      const glyph = glyphs[Math.min(glyphs.length - 1, Math.abs(value))] ?? '';
      context.font = `${step * 0.92}px "Segoe UI Symbol", "Noto Sans Symbols 2", "DejaVu Sans", serif`;
      context.textAlign = 'center';
      context.textBaseline = 'middle';
      context.lineWidth = Math.max(1, step * 0.04);
      context.strokeStyle = 'rgba(23, 33, 39, 0.9)';
      context.strokeText(glyph, cx, cy);
      context.fillStyle = value > 0 ? '#FFFFFF' : '#1B252B';
      context.fillText(glyph, cx, cy);
      break;
    }
    case 'xiangqi': {
      const glyphs = value > 0 ? XIANGQI_RED : XIANGQI_BLACK;
      const glyph = glyphs[Math.min(glyphs.length - 1, Math.abs(value))] ?? '';
      context.fillStyle = '#FFF1CF';
      context.beginPath();
      context.arc(cx, cy, step * 0.43, 0, Math.PI * 2);
      context.fill();
      context.strokeStyle = value > 0 ? '#A22727' : '#26302E';
      context.lineWidth = Math.max(1, step * 0.035);
      context.beginPath();
      context.arc(cx, cy, step * 0.4, 0, Math.PI * 2);
      context.stroke();
      drawLabel(context, glyph, cx, cy, step * 0.6, context.strokeStyle as string);
      break;
    }
    case 'go':
    case 'gomoku': {
      const black = value === 1 || value === -1 ? true : value === 2 || value === -2 ? false : value > 0;
      context.beginPath();
      context.arc(cx, cy + step * 0.04, step * 0.44, 0, Math.PI * 2);
      context.fillStyle = 'rgba(20, 26, 28, 0.22)';
      context.fill();
      context.beginPath();
      context.arc(cx, cy, step * 0.44, 0, Math.PI * 2);
      context.fillStyle = black ? '#1D262A' : '#FAFAF6';
      context.fill();
      context.strokeStyle = black ? 'rgba(255, 255, 255, 0.12)' : 'rgba(60, 66, 64, 0.4)';
      context.lineWidth = 1;
      context.stroke();
      if (moveNumber !== undefined && moveNumber > 0) {
        drawLabel(context, String(moveNumber), cx, cy, step * 0.42, black ? 'rgba(255,255,255,0.85)' : 'rgba(30,36,40,0.8)');
      }
      if (dead) {
        context.strokeStyle = cssColor(palette.danger);
        context.lineWidth = Math.max(1.5, step * 0.07);
        context.beginPath();
        context.moveTo(cx - step * 0.22, cy - step * 0.22);
        context.lineTo(cx + step * 0.22, cy + step * 0.22);
        context.moveTo(cx + step * 0.22, cy - step * 0.22);
        context.lineTo(cx - step * 0.22, cy + step * 0.22);
        context.stroke();
      }
      break;
    }
    case 'reversi': {
      const black = value > 0 ? Math.abs(value) === 1 : Math.abs(value) === 2;
      context.beginPath();
      context.arc(cx, cy, step * 0.38, 0, Math.PI * 2);
      context.fillStyle = black ? '#1D262A' : '#FAFAF6';
      context.fill();
      context.strokeStyle = 'rgba(60, 66, 64, 0.45)';
      context.lineWidth = 1;
      context.stroke();
      break;
    }
    case 'draughts': {
      context.beginPath();
      context.arc(cx, cy, step * 0.4, 0, Math.PI * 2);
      context.fillStyle = value > 0 ? '#FAF9F0' : '#1D2628';
      context.fill();
      context.strokeStyle = 'rgba(91, 101, 99, 0.6)';
      context.lineWidth = 1;
      context.stroke();
      if (Math.abs(value) > 1) {
        drawLabel(context, '王', cx, cy, step * 0.48, value > 0 ? '#1D2628' : '#FAF9F0');
      }
      break;
    }
    case 'chinese_checkers': {
      const seat = Math.abs(value) - 1;
      context.beginPath();
      context.arc(cx, cy, step * 0.42, 0, Math.PI * 2);
      context.fillStyle = PLAYER_COLORS[Math.min(2, Math.max(0, seat))];
      context.fill();
      context.strokeStyle = 'rgba(20, 26, 28, 0.25)';
      context.lineWidth = 1;
      context.stroke();
      break;
    }
    default: {
      const seat = Math.abs(value) - 1;
      context.beginPath();
      context.arc(cx, cy, step * 0.4, 0, Math.PI * 2);
      context.fillStyle = value > 0 ? PLAYER_COLORS[Math.min(2, Math.max(0, seat))] : cssColor(darkSquare);
      context.fill();
      context.strokeStyle = 'rgba(60, 66, 64, 0.45)';
      context.lineWidth = 1;
      context.stroke();
      break;
    }
  }
  context.restore();
}

function drawMemoryCard(
  context: CanvasRenderingContext2D,
  value: number,
  index: number,
  cx: number,
  cy: number,
  step: number,
  palette: Palette,
  owner: number,
  revealed: boolean,
): void {
  const half = step * 0.43;
  context.save();
  context.beginPath();
  context.roundRect(cx - half, cy - half, half * 2, half * 2, Math.min(10, step * 0.12));
  if (owner >= 0) context.fillStyle = PLAYER_COLORS[Math.min(2, owner)];
  else if (value < 0) context.fillStyle = cssColor(palette.accent);
  else context.fillStyle = '#FFFDF6';
  context.fill();
  context.strokeStyle = revealed ? AMBER : withAlpha(cssColor(palette.muted), 0.5);
  context.lineWidth = revealed ? Math.max(2, step * 0.05) : 1;
  context.stroke();
  if (value >= 0) {
    drawLabel(context, String(value + 1), cx, cy, step * 0.5, owner >= 0 ? '#FFFFFF' : cssColor(palette.text));
  } else {
    context.strokeStyle = withAlpha('#FFFFFF', 0.7);
    context.lineWidth = Math.max(1, step * 0.03);
    for (const offset of [-1, 0, 1]) {
      context.beginPath();
      context.moveTo(cx - half * 0.55, cy + offset * step * 0.16);
      context.lineTo(cx + half * 0.55, cy + offset * step * 0.16);
      context.stroke();
    }
  }
  context.restore();
}

function drawArena(
  context: CanvasRenderingContext2D,
  snapshot: GameSnapshot,
  palette: Palette,
  geometry: Geometry,
  width: number,
  height: number,
  highlights: Set<number>,
  selected: number[],
): void {
  const { left, top, step, gridWidth, gridHeight } = geometry;
  context.save();
  context.fillStyle = cssColor(palette.background);
  context.beginPath();
  context.roundRect(left, top, gridWidth, gridHeight, Math.min(18, step * 0.4));
  context.fill();
  context.strokeStyle = withAlpha(cssColor(palette.muted), 0.35);
  context.lineWidth = 1;
  context.stroke();

  for (let index = 0; index < snapshot.cells.length; index += 1) {
    const value = snapshot.cells[index];
    if (value === 0) continue;
    const x = left + (index % snapshot.width) * step;
    const y = top + Math.floor(index / snapshot.width) * step;
    context.fillStyle = value === -2 ? '#A97D49' : withAlpha(cssColor(palette.muted), 0.8);
    context.beginPath();
    context.roundRect(x + step * 0.06, y + step * 0.06, step * 0.88, step * 0.88, Math.min(6, step * 0.16));
    context.fill();
  }

  for (const entity of snapshot.entities) {
    const cx = left + (entity.x + 0.5) * step;
    const cy = top + (entity.y + 0.5) * step;
    const halfWidth = (entity.width || 1) * step * 0.45;
    const halfHeight = (entity.height || 1) * step * 0.45;
    const color = entity.owner >= 0 && entity.owner <= 2 ? PLAYER_COLORS[entity.owner] : cssColor(palette.danger);
    switch (entity.kind) {
      case 'food': {
        context.fillStyle = '#D28C1D';
        context.beginPath();
        context.arc(cx, cy, step * 0.22, 0, Math.PI * 2);
        context.fill();
        break;
      }
      case 'bomb': {
        context.fillStyle = '#232D32';
        context.beginPath();
        context.arc(cx, cy, step * 0.33, 0, Math.PI * 2);
        context.fill();
        drawLabel(context, '!', cx, cy, step * 0.5, '#FFFFFF');
        break;
      }
      case 'blast': {
        context.fillStyle = withAlpha('#E89B28', Math.min(0.85, 0.25 + 0.6 * Math.max(0, entity.value) / 500));
        context.fillRect(cx - halfWidth, cy - halfHeight, halfWidth * 2, halfHeight * 2);
        break;
      }
      case 'bullet': {
        context.fillStyle = color;
        context.beginPath();
        context.arc(cx, cy, step * 0.13, 0, Math.PI * 2);
        context.fill();
        break;
      }
      case 'obstacle': {
        context.fillStyle = cssColor(palette.danger);
        context.beginPath();
        context.roundRect(cx - halfWidth, cy - halfHeight, halfWidth * 2, halfHeight * 2, step * 0.14);
        context.fill();
        break;
      }
      default: {
        context.save();
        context.translate(cx, cy);
        context.rotate((entity.angle * Math.PI) / 180);
        context.fillStyle = color;
        const isSnakeBody = entity.kind === 'snake';
        if (isSnakeBody) {
          context.beginPath();
          context.arc(0, 0, step * 0.33, 0, Math.PI * 2);
          context.fill();
        } else {
          context.beginPath();
          context.roundRect(-halfWidth, -halfHeight, halfWidth * 2, halfHeight * 2, step * 0.2);
          context.fill();
          if (snapshot.gameId === 'tank' && entity.kind === 'player') {
            context.strokeStyle = 'rgba(255, 255, 255, 0.85)';
            context.lineWidth = Math.max(2, step * 0.12);
            context.beginPath();
            context.moveTo(0, 0);
            context.lineTo(0, -step * 0.6);
            context.stroke();
          }
        }
        context.restore();
        if (entity.kind === 'player') {
          drawLabel(context, String(entity.owner + 1), cx, cy, step * 0.42, '#FFFFFF');
        }
        break;
      }
    }
  }

  for (const index of highlights) {
    const cx = left + (index % snapshot.width) * step + step / 2;
    const cy = top + Math.floor(index / snapshot.width) * step + step / 2;
    context.strokeStyle = cssColor(palette.accent);
    context.lineWidth = 2;
    context.beginPath();
    context.roundRect(cx - step * 0.46, cy - step * 0.46, step * 0.92, step * 0.92, step * 0.12);
    context.stroke();
  }
  for (const index of selected) {
    const cx = left + (index % snapshot.width) * step + step / 2;
    const cy = top + Math.floor(index / snapshot.width) * step + step / 2;
    context.strokeStyle = AMBER;
    context.lineWidth = 2;
    context.beginPath();
    context.roundRect(cx - step * 0.46, cy - step * 0.46, step * 0.92, step * 0.92, step * 0.12);
    context.stroke();
  }
  context.restore();
}

function parseCsv(value: string | undefined): number[] {
  if (!value) return [];
  return value.split(',').map((entry) => Number.parseInt(entry, 10)).filter((entry) => Number.isFinite(entry));
}

function parseGtpMoves(value: string, width: number, height: number): Map<number, number> {
  const moves = new Map<number, number>();
  if (!value) return moves;
  const tokens = value.trim().split(/\s+/);
  tokens.forEach((token, index) => {
    const cell = gtpIndex(token, width, height);
    if (cell >= 0) moves.set(cell, index + 1);
  });
  return moves;
}
