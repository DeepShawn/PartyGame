/** 街机类游戏的展示区与操作面板（反应/颜色/心算 + 8 款通用方向控制）。 */
import { useCallback, useEffect, useRef } from 'react';
import type { GameAction, GameSnapshot } from '../net/types';
import { arcadeAllowed, canAct } from './input';

export function ArcadeStage({ snapshot, sound }: { snapshot: GameSnapshot; sound: boolean }) {
  const gameId = snapshot.gameId;
  if (gameId === 'reaction') {
    const phase = snapshot.phase;
    const text = phase === 'signal' ? '现在！' : phase === 'waiting' ? '等待信号' : phase === 'result' ? '本轮结束' : '比赛结束';
    return (
      <div className="arcade-stage">
        <div className={`reaction-display${phase === 'signal' && sound ? ' reaction-pulse' : ''}${phase === 'signal' ? ' reaction-signal' : ''}`}>
          {text}
        </div>
        <p className="arcade-hint">信号出现后立刻按自己的「抢按」；提前抢按本轮不得分。</p>
      </div>
    );
  }
  if (gameId === 'color') {
    const index = Number.parseInt(snapshot.extra['colorIndex'] ?? '-1', 10);
    const colors = (snapshot.extra['palette'] ?? '').split(',').filter((value) => /^#[0-9a-fA-F]{6}$/.test(value));
    const color = colors[index] ?? 'var(--text)';
    return (
      <div className="arcade-stage">
        <div className="color-card">
          <span className="color-question" style={{ color }}>{snapshot.extra['question'] ?? ''}</span>
        </div>
        <p className="arcade-hint">选出文字的实际颜色，而不是字义。</p>
      </div>
    );
  }
  if (gameId === 'math') {
    return (
      <div className="arcade-stage">
        <div className="math-question">{snapshot.extra['question'] ?? ''}</div>
        <p className="arcade-hint">从四个选项中选出正确结果，答错会扣分。</p>
      </div>
    );
  }
  return null;
}

interface ControlsProps {
  snapshot: GameSnapshot;
  viewer: number;
  localSeats: number[];
  onAction: (seat: number, action: GameAction) => void;
}

export function ArcadeControls({ snapshot, viewer, localSeats, onAction }: ControlsProps) {
  const desired = useRef(new Map<number, number>());
  const ticks = useRef(new Map<number, number>());
  const snapshotRef = useRef(snapshot);
  snapshotRef.current = snapshot;

  const sendDirection = useCallback((seat: number) => {
    const current = snapshotRef.current;
    const direction = desired.current.get(seat);
    if (direction === undefined) return;
    if (ticks.current.get(seat) === current.tick) return;
    if (!canAct(current, localSeats, viewer, seat)) { desired.current.delete(seat); return; }
    const action: GameAction = { type: 'direction', values: [direction], text: '' };
    if (!arcadeAllowed(current.gameId, current, seat, viewer, action)) return;
    ticks.current.set(seat, current.tick);
    onAction(seat, action);
  }, [localSeats, viewer, onAction]);

  useEffect(() => {
    const current = snapshotRef.current;
    const directions = (current.extra['directions'] ?? '').split(',');
    for (const seat of [...desired.current.keys()]) {
      const applied = Number.parseInt(directions[seat] ?? '', 10);
      if (applied === desired.current.get(seat)) {
        if (desired.current.get(seat) === -1) desired.current.delete(seat);
      } else {
        sendDirection(seat);
      }
    }
  }, [snapshot, sendDirection]);

  useEffect(() => () => { desired.current.clear(); ticks.current.clear(); }, []);

  const seats = localSeats.filter((seat) => seat >= 0 && seat < snapshot.players);
  if (seats.length === 0) return null;

  const renderDirectional = (seat: number) => {
    const pad = (label: string, direction: number) => {
      const action: GameAction = { type: 'direction', values: [direction], text: '' };
      const enabled = canAct(snapshot, localSeats, viewer, seat) && arcadeAllowed(snapshot.gameId, snapshot, seat, viewer, action);
      const release = () => {
        if (desired.current.get(seat) === direction) {
          desired.current.set(seat, -1);
          sendDirection(seat);
        }
      };
      return (
        <button
          key={label}
          type="button"
          className="pad-btn"
          disabled={!enabled}
          aria-label={`${seat + 1}号 ${label}`}
          onPointerDown={(event) => {
            event.currentTarget.setPointerCapture(event.pointerId);
            desired.current.set(seat, direction);
            sendDirection(seat);
          }}
          onPointerUp={release}
          onPointerCancel={release}
          onLostPointerCapture={release}
          onContextMenu={(event) => event.preventDefault()}
        >
          {label}
        </button>
      );
    };
    const actionButton = (label: string, action: GameAction, primary = false) => {
      const enabled = arcadeAllowed(snapshot.gameId, snapshot, seat, viewer, action);
      return (
        <button
          key={label}
          type="button"
          className={`pad-btn pad-action${primary ? ' pad-primary' : ''}`}
          disabled={!enabled}
          aria-label={`${seat + 1}号 ${label}`}
          onClick={() => onAction(seat, action)}
        >
          {label}
        </button>
      );
    };
    return (
      <div className="pad" key={seat}>
        <span className="pad-title">{seat + 1} 号</span>
        {snapshot.gameId === 'reaction' ? (
          <div className="pad-row">{actionButton('抢按', { type: 'tap', values: [], text: '' }, true)}</div>
        ) : snapshot.gameId === 'color' || snapshot.gameId === 'math' || ['coinflip', 'numberguess', 'oddone', 'targettap', 'pattern', 'sumten', 'highlow', 'color_match', 'memoryrush', 'speedchoice'].includes(snapshot.gameId) ? (
          <>
            <div className="pad-row">
              {[0, 1].map((index) => actionButton(snapshot.labels[index] ?? `选项 ${index + 1}`, { type: snapshot.gameId === 'color' || snapshot.gameId === 'math' ? 'answer' : 'choose', values: [index], text: '' }))}
            </div>
            <div className="pad-row">
              {[2, 3].map((index) => actionButton(snapshot.labels[index] ?? `选项 ${index + 1}`, { type: snapshot.gameId === 'color' || snapshot.gameId === 'math' ? 'answer' : 'choose', values: [index], text: '' }))}
            </div>
          </>
        ) : (
          <>
            <div className="pad-row">
              {pad('↑', 0)}
              {pad('→', 1)}
            </div>
            <div className="pad-row">
              {pad('←', 3)}
              {pad('↓', 2)}
            </div>
            <div className="pad-row">
              {pad('停止', -1)}
              {snapshot.gameId === 'tank' ? actionButton('开火', { type: 'fire', values: [], text: '' }, true) : null}
              {snapshot.gameId === 'bomber' ? actionButton('炸弹', { type: 'bomb', values: [], text: '' }, true) : null}
            </div>
          </>
        )}
      </div>
    );
  };

  return <div className="pads">{seats.map(renderDirectional)}</div>;
}
