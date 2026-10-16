/** 对局主界面：本机同屏与联机共用。所有裁决在引擎/房主端，本组件只负责选择、确认与提交。 */
import { useCallback, useEffect, useMemo, useRef, useState, type PointerEvent, type ReactNode } from 'react';
import { findGame } from '../core/engine';
import type { GameAction, GameSnapshot, RoomMember } from '../net/types';
import { useSettings } from '../app/settings';
import { playAlert, playSignal } from '../app/sound';
import { Button, Modal } from '../app/Ui';
import { ArcadeControls, ArcadeStage } from './Arcade';
import { BoardCanvas } from './BoardCanvas';
import { DouDizhuPanel } from './Cards';
import { actionLabel, arcadeAllowed, canAct, isRealtime, movesAt, nextTargets, sameAction, squareLabel } from './input';

interface Props {
  snapshot: GameSnapshot;
  viewer: number;
  localSeats: number[];
  members?: RoomMember[];
  onAction: (seat: number, action: GameAction) => void;
  onLeave: () => void;
  footer?: ReactNode;
  handover?: boolean;
  notice?: string;
}

export function MatchView({ snapshot, viewer, localSeats, members, onAction, onLeave, footer, handover, notice }: Props) {
  const { settings, palette } = useSettings();
  const gameId = snapshot.gameId;
  const info = useMemo(() => {
    try {
      return findGame(gameId);
    } catch {
      return null;
    }
  }, [gameId]);

  const [path, setPath] = useState<number[]>([]);
  const [pending, setPending] = useState<GameAction | null>(null);
  const [promotion, setPromotion] = useState<GameAction[] | null>(null);
  const [legalOpen, setLegalOpen] = useState(false);
  const [rulesOpen, setRulesOpen] = useState(false);
  const [reviewOpen, setReviewOpen] = useState(false);
  const [reviewIndex, setReviewIndex] = useState(0);
  const [reviewFrames, setReviewFrames] = useState<GameSnapshot[]>([]);
  const [confirming, setConfirming] = useState<GameAction | null>(null);
  const [revealedSeat, setRevealedSeat] = useState<number | null>(null);
  const [handoverSkipped, setHandoverSkipped] = useState(false);
  const [feedback, setFeedback] = useState('');
  const feedbackTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const previous = useRef<GameSnapshot | null>(null);
  const previousView = useRef('');
  const previousPhase = useRef(snapshot.phase);
  const gestureStart = useRef<{ x: number; y: number; pointerId: number } | null>(null);
  const seatsKey = localSeats.join(',');

  const notify = useCallback((message: string) => {
    setFeedback(message);
    if (feedbackTimer.current) clearTimeout(feedbackTimer.current);
    feedbackTimer.current = setTimeout(() => setFeedback(''), 4000);
  }, []);
  useEffect(() => () => { if (feedbackTimer.current) clearTimeout(feedbackTimer.current); }, []);

  const actionable = useCallback((seat: number) => canAct(snapshot, localSeats, viewer, seat), [snapshot, localSeats, viewer]);

  const handVisible = gameId !== 'doudizhu' ? true
    : localSeats.length === 1
      ? viewer >= 0 && localSeats.includes(viewer) && (snapshot.extra['viewer'] ?? String(viewer)) === String(viewer)
      : viewer === snapshot.turn && revealedSeat === viewer && localSeats.includes(viewer);

  const decide = useCallback((seat: number, action: GameAction) => {
    if (!actionable(seat)) { notify('等待当前玩家操作。'); return; }
    const allowed = isRealtime(gameId)
      ? arcadeAllowed(gameId, snapshot, seat, viewer, action)
      : gameId === 'doudizhu' && action.type === 'play'
        ? action.values.length > 0 && action.values.every((card) => snapshot.hand.includes(card))
        : snapshot.legal.some((entry) => sameAction(entry, action));
    if (!allowed) { notify('该操作当前不可用，已同步最新局面。'); return; }
    setPath([]);
    setPending(null);
    setPromotion(null);
    onAction(seat, action);
  }, [actionable, gameId, notify, onAction, snapshot, viewer]);

  const submit = useCallback((seat: number, action: GameAction) => {
    if (gameId === 'doudizhu' && !handVisible) { notify('请先确认身份查看手牌。'); return; }
    decide(seat, action);
  }, [decide, gameId, handVisible, notify]);

  const propose = useCallback((action: GameAction) => {
    if (!actionable(viewer)) return;
    if (!snapshot.legal.some((entry) => sameAction(entry, action))) return;
    setPending(action);
    if (!settings.confirmMoves) submit(viewer, action);
  }, [actionable, viewer, snapshot.legal, settings.confirmMoves, submit]);

  const selectCell = useCallback((index: number) => {
    if (!actionable(viewer)) return;
    const direct = snapshot.legal.find((action) => (action.type === 'place' || action.type === 'flip' || action.type === 'dead')
      && action.values.length === 1 && action.values[0] === index);
    if (direct) {
      setPath([index]);
      if (direct.type === 'flip') submit(viewer, direct);
      else propose(direct);
      return;
    }
    const moves = snapshot.legal.filter((action) => action.type === 'move');
    let candidates = movesAt(moves, [...path, index]);
    let nextPath = [...path, index];
    if (path.length === 0 || candidates.length === 0 || pending) {
      nextPath = [index];
      candidates = movesAt(moves, nextPath);
      if (candidates.length === 0) nextPath = [];
      setPending(null);
    }
    setPath(nextPath);
    if (nextPath.length >= 2 && candidates.length > 0) {
      if (gameId === 'chess' && candidates.some((action) => action.values.length === 3)) {
        setPromotion(candidates.filter((action) => action.values.length === 3));
        return;
      }
      const exact = candidates.find((action) => action.values.length === nextPath.length);
      if (exact) propose(exact);
    }
  }, [actionable, viewer, snapshot.legal, path, pending, gameId, submit, propose]);

  // 与安卓端一致：局面、轮到谁、阶段或（回合制游戏）合法列表变化时回到未选择状态。
  useEffect(() => {
    const old = previous.current;
    previous.current = snapshot;
    const turnChanged = old ? old.turn !== snapshot.turn || old.gameId !== snapshot.gameId : false;
    const changed = !old || old.gameId !== snapshot.gameId || old.turn !== snapshot.turn || old.phase !== snapshot.phase
      || old.winner !== snapshot.winner || !sameNumbers(old.cells, snapshot.cells)
      || (!isRealtime(gameId) && !sameLegal(old.legal, snapshot.legal));
    if (changed) {
      setPath([]);
      setPending(null);
      setPromotion(null);
    }
    if (turnChanged && gameId === 'doudizhu') {
      setRevealedSeat(null);
      setHandoverSkipped(false);
    }
  }, [snapshot, gameId]);

  useEffect(() => {
    if (info?.category !== 'board') return;
    setReviewFrames((frames) => {
      const last = frames[frames.length - 1];
      if (last && last.tick === snapshot.tick && last.winner === snapshot.winner) return frames;
      const next = [...frames, snapshot].slice(-160);
      setReviewIndex(next.length - 1);
      return next;
    });
  }, [snapshot, info?.category]);

  useEffect(() => {
    const key = `${viewer}|${seatsKey}`;
    if (previousView.current === key) return;
    previousView.current = key;
    setPath([]);
    setPending(null);
    setPromotion(null);
    setRevealedSeat(null);
    setHandoverSkipped(false);
  }, [viewer, seatsKey]);

  useEffect(() => {
    if (previousPhase.current !== 'signal' && snapshot.phase === 'signal' && gameId === 'reaction') playSignal(settings.sound);
    if (previousPhase.current !== 'finished' && snapshot.phase === 'finished') playAlert(settings.sound);
    previousPhase.current = snapshot.phase;
  }, [snapshot.phase, gameId, settings.sound]);

  const targets = useMemo(() => {
    if (!actionable(viewer)) return new Set<number>();
    if (path.length > 0 && !pending) return nextTargets(snapshot.legal, path);
    const values = new Set<number>();
    for (const action of snapshot.legal) {
      if (action.type === 'move' || action.type === 'place' || action.type === 'flip' || action.type === 'dead') {
        if (action.values.length > 0) values.add(action.values[0]);
      }
    }
    return values;
  }, [snapshot.legal, path, pending, viewer, actionable]);

  const extraActions = useMemo(() => {
    const relevant = snapshot.legal.filter((action) => ['resign', 'pass', 'accept_score', 'resume', 'claim_draw', 'bid', 'hint'].includes(action.type));
    const seen = new Set<string>();
    return relevant.filter((action) => {
      const key = `${action.type}|${action.values.join(',')}`;
      if (seen.has(key)) return false;
      seen.add(key);
      return true;
    });
  }, [snapshot.legal]);

  const winner = snapshot.winner;
  const resultText = winner === -1 ? '本局结束 · 平局'
    : winner !== null && gameId === 'doudizhu'
      ? `本局结束 · ${snapshot.extra['winningSide'] === 'landlord' ? '地主方' : '农民方'}获胜`
      : winner !== null ? `本局结束 · ${winner + 1} 号获胜` : '';

  const countdown = Number.parseInt(snapshot.extra['countdown'] ?? '', 10);
  const round = snapshot.extra['round'];
  const turnLabel = snapshot.turn >= 0 ? `当前：${snapshot.turn + 1} 号` : localSeats.length > 1 ? '每人使用自己的操作区' : '无固定顺序';
  const phaseText = [
    round ? `第 ${round} / ${snapshot.extra['totalRounds'] ?? '?'} 轮` : '',
    Number.isFinite(countdown) && countdown > 0 ? `${Math.ceil(countdown / 1000)} 秒` : '',
    localSeats.length === 0 ? '观战模式 · 不能操作' : turnLabel,
  ].filter(Boolean).join('  ·  ');

  const seatTitle = (seat: number) => {
    const member = members?.find((entry) => entry.seat === seat);
    const base = info?.category === 'board' ? snapshot.labels[seat] ?? `${seat + 1} 号` : `${seat + 1} 号`;
    const name = member ? `${member.name} · ${base}` : base;
    const tag = localSeats.includes(seat) ? (members ? '你' : '本机') : '';
    return tag ? `${name} · ${tag}` : name;
  };

  const seatDetail = (seat: number) => {
    const alive = (snapshot.extra['alive'] ?? '').split(',')[seat];
    const hp = (snapshot.extra['hp'] ?? '').split(',')[seat];
    if (gameId === 'doudizhu') {
      return `${snapshot.counts[seat] ?? 0} 张${snapshot.extra['landlord'] === String(seat) ? ' · 地主' : ''}`;
    }
    if (alive === '0') return '已出局';
    if (hp !== undefined && hp !== '') return `生命 ${hp} · ${snapshot.scores[seat] ?? 0} 分`;
    if (snapshot.scores.length > 0) return `${snapshot.scores[seat] ?? 0} 分`;
    if (snapshot.turn === seat) return '正在思考';
    return '等待';
  };

  const showSelection = gameId === 'memory' || (info?.category === 'board') || (!isRealtime(gameId) && gameId !== 'doudizhu' && snapshot.width > 0);
  const selectionText = !actionable(viewer)
    ? localSeats.length === 0 ? '正在观战' : '等待当前玩家操作'
    : pending
      ? `已选：${actionLabel(gameId, pending, snapshot.width)}`
      : path.length > 0
        ? `${path.map((value) => squareLabel(value, snapshot.width)).join(' → ')} · 点选高亮目标继续`
        : `点击棋子或高亮落点${settings.confirmMoves ? '，再确认' : ''}`;

  const promotionOptions = promotion?.map((action) => ({ action, label: `升${action.values[2] === 2 ? '马' : action.values[2] === 3 ? '象' : action.values[2] === 4 ? '车' : '后'}` }));

  const handoverVisible = Boolean(handover) && gameId === 'doudizhu' && localSeats.length > 1 && !handVisible
    && !handoverSkipped && snapshot.phase !== 'finished' && viewer >= 0;
  const gestureGame = ['snake', 'tank', 'bomber', 'dodge'].includes(gameId);
  const onGestureStart = (event: PointerEvent<HTMLDivElement>) => {
    if (gestureGame && event.isPrimary) { event.preventDefault(); gestureStart.current = { x: event.clientX, y: event.clientY, pointerId: event.pointerId }; event.currentTarget.setPointerCapture?.(event.pointerId); }
  };
  const onGestureEnd = (event: PointerEvent<HTMLDivElement>) => {
    const start = gestureStart.current; gestureStart.current = null;
    if (!start || !gestureGame || start.pointerId !== event.pointerId) return;
    event.preventDefault(); const dx = event.clientX - start.x; const dy = event.clientY - start.y;
    if (Math.max(Math.abs(dx), Math.abs(dy)) < 28) return;
    const direction = Math.abs(dx) > Math.abs(dy) ? (dx > 0 ? 1 : 3) : (dy > 0 ? 2 : 0);
    submit(viewer, { type: 'direction', values: [direction], text: '' });
  };

  return (
    <div className="match">
      <header className="match-head">
        <Button compact onClick={onLeave}>离开</Button>
        <h2>{info?.title ?? gameId}</h2>
        <div className="match-head-actions"><Button compact onClick={() => setRulesOpen(true)}>规则</Button>{info?.category === 'board' ? <Button compact onClick={() => setReviewOpen(true)}>复盘</Button> : null}</div>
      </header>

      <div className="score-row">
        {Array.from({ length: snapshot.players }, (_, seat) => (
          <div key={seat} className={`score-chip${snapshot.turn === seat ? ' score-active' : ''}${winner === seat ? ' score-winner' : ''}`}>
            <strong>{seatTitle(seat)}</strong>
            <span>{seatDetail(seat)}</span>
          </div>
        ))}
      </div>

      <div className="match-status">
        <p className="match-message">{resultText || snapshot.message}</p>
        <p className="match-phase">{phaseText}</p>
        {notice ? <p className="match-notice">{notice}</p> : null}
        {feedback ? <p className="match-notice">{feedback}</p> : null}
      </div>

      <div className="match-body">
        <div className={`match-main${gestureGame ? ' gesture-surface' : ''}`} onPointerDown={onGestureStart} onPointerUp={onGestureEnd} onPointerCancel={() => { gestureStart.current = null; }}>
          {gameId === 'reaction' || gameId === 'color' || gameId === 'math' ? (
            <ArcadeStage snapshot={snapshot} sound={settings.sound} />
          ) : gameId === 'doudizhu' ? (
            <DouDizhuPanel
              snapshot={snapshot}
              viewer={viewer}
              localSeats={localSeats}
              handVisible={handVisible}
              onAction={submit}
              onFeedback={notify}
            />
          ) : snapshot.width > 0 ? (
            <BoardCanvas
              snapshot={snapshot}
              palette={palette}
              coordinates={settings.coordinates}
              highlights={targets}
              selected={path}
              onCell={selectCell}
            />
          ) : null}
          {isRealtime(gameId) && gameId !== 'reaction' && gameId !== 'color' && gameId !== 'math' ? (
            <ArcadeControls snapshot={snapshot} viewer={viewer} localSeats={localSeats} onAction={submit} />
          ) : null}
        </div>

        <div className="match-side">
          {showSelection ? (
            <>
              <p className="selection-text">{selectionText}</p>
              <div className="action-row">
                <Button variant="primary" compact disabled={!pending || !actionable(viewer)} onClick={() => pending && submit(viewer, pending)}>确认落子</Button>
                <Button compact disabled={path.length === 0 && !pending} onClick={() => { setPath([]); setPending(null); }}>重新选择</Button>
              </div>
              <Button compact block disabled={!actionable(viewer) || snapshot.legal.length === 0} onClick={() => setLegalOpen(true)}>合法走法列表</Button>
            </>
          ) : null}

          {isRealtime(gameId) && (gameId === 'reaction' || gameId === 'color' || gameId === 'math') ? (
            <ArcadeControls snapshot={snapshot} viewer={viewer} localSeats={localSeats} onAction={submit} />
          ) : null}

          <div className="extras">
            {extraActions.map((action) => (
              <Button
                key={`${action.type}-${action.values.join('-')}`}
                compact
                disabled={!actionable(viewer)}
                onClick={() => {
                  if (action.type === 'resign' || action.type === 'accept_score') setConfirming(action);
                  else submit(viewer, action);
                }}
              >
                {actionLabel(gameId, action, snapshot.width)}
              </Button>
            ))}
          </div>

          {footer}
        </div>
      </div>

      {handoverVisible ? (
        <div className="handover">
          <div className="handover-card">
            <h3>把设备交给下一位玩家</h3>
            <p>本回合由 {viewer + 1} 号玩家操作。</p>
            <Button variant="primary" onClick={() => setRevealedSeat(viewer)}>{`我是 ${viewer + 1} 号，查看手牌`}</Button>
            <Button compact onClick={() => setHandoverSkipped(true)}>暂不查看</Button>
          </div>
        </div>
      ) : null}

      {promotionOptions ? (
        <Modal title="选择升变棋子" subtitle="兵到底线后必须升变" onClose={() => { setPromotion(null); setPath([]); }}>
          <div className="action-row">
            {promotionOptions.map((option) => (
              <Button key={option.label} onClick={() => { setPromotion(null); propose(option.action); }}>{option.label}</Button>
            ))}
          </div>
        </Modal>
      ) : null}

      {legalOpen ? (
        <Modal title="合法走法列表" subtitle="选择后可直接确认提交" onClose={() => setLegalOpen(false)} wide>
          <div className="legal-list">
            {snapshot.legal
              .filter((action) => ['move', 'place', 'dead', 'flip'].includes(action.type))
              .map((action, index) => (
                <Button
                  key={`${action.type}-${action.values.join('-')}-${index}`}
                  compact
                  onClick={() => {
                    setLegalOpen(false);
                    setPath(gameId === 'chess' ? action.values.slice(0, 2) : action.values);
                    if (action.type === 'flip') submit(viewer, action);
                    else propose(action);
                  }}
                >
                  {actionLabel(gameId, action, snapshot.width)}
                </Button>
              ))}
          </div>
        </Modal>
      ) : null}

      {rulesOpen ? (
        <Modal title={info?.title ?? gameId} subtitle={info?.subtitle} onClose={() => setRulesOpen(false)} wide>
          <p className="rules-text">{snapshot.extra['rules'] ?? info?.rules ?? '暂无规则说明。'}</p>
        </Modal>
      ) : null}
      {reviewOpen && info?.category === 'board' ? (
        <Modal title="棋局复盘" subtitle="拖动进度查看每一步局面" onClose={() => setReviewOpen(false)} wide>
          <div className="review-toolbar"><strong>第 {reviewIndex} 手 / {Math.max(0, reviewFrames.length - 1)}</strong><Button compact disabled={reviewIndex <= 0} onClick={() => setReviewIndex((v) => Math.max(0, v - 1))}>上一手</Button><Button compact disabled={reviewIndex >= reviewFrames.length - 1} onClick={() => setReviewIndex((v) => Math.min(reviewFrames.length - 1, v + 1))}>下一手</Button></div>
          <input className="review-slider" type="range" min={0} max={Math.max(0, reviewFrames.length - 1)} value={reviewIndex} onChange={(e) => setReviewIndex(Number(e.target.value))} aria-label="复盘进度" />
          {reviewFrames[reviewIndex] ? <BoardCanvas snapshot={reviewFrames[reviewIndex]} palette={palette} coordinates={settings.coordinates} highlights={new Set()} selected={[]} onCell={() => undefined} /> : <p>暂无复盘记录</p>}
          <p className="card-hint">{reviewFrames[reviewIndex]?.message ?? '暂无局面说明'} · 可在规则中查看完整玩法。</p>
        </Modal>
      ) : null}

      {confirming ? (
        <Modal
          title={`确认${actionLabel(gameId, confirming, snapshot.width)}？`}
          subtitle="该操作会立即生效"
          onClose={() => setConfirming(null)}
        >
          <div className="action-row">
            <Button variant="danger" onClick={() => { const action = confirming; setConfirming(null); submit(viewer, action); }}>确认</Button>
            <Button onClick={() => setConfirming(null)}>取消</Button>
          </div>
        </Modal>
      ) : null}
    </div>
  );
}

function sameNumbers(left: number[], right: number[]): boolean {
  if (left === right) return true;
  if (left.length !== right.length) return false;
  for (let index = 0; index < left.length; index += 1) if (left[index] !== right[index]) return false;
  return true;
}

function sameLegal(left: GameAction[], right: GameAction[]): boolean {
  if (left.length !== right.length) return false;
  for (let index = 0; index < left.length; index += 1) if (!sameAction(left[index], right[index])) return false;
  return true;
}
