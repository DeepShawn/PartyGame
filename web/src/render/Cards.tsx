/** 斗地主：底牌与上手信息、手牌多选、动作按钮（不出/叫分/提示）。 */
import { useEffect, useMemo, useState } from 'react';
import type { GameAction, GameSnapshot } from '../net/types';
import { actionLabel, canAct, cardIsRed, cardLabel } from './input';

interface Props {
  snapshot: GameSnapshot;
  viewer: number;
  localSeats: number[];
  handVisible: boolean;
  onAction: (seat: number, action: GameAction) => void;
  onFeedback: (message: string) => void;
}

export function DouDizhuPanel({ snapshot, viewer, localSeats, handVisible, onAction, onFeedback }: Props) {
  const [selected, setSelected] = useState<number[]>([]);
  const hand = handVisible ? snapshot.hand : [];

  const handKey = hand.join(',');
  useEffect(() => {
    setSelected((current) => (current.length === 0 ? current : current.filter((card) => hand.includes(card))));
    // eslint-disable-next-line react-hooks/exhaustive-deps -- handKey 已覆盖手牌内容变化
  }, [handKey]);

  const actionable = canAct(snapshot, localSeats, viewer, viewer) && handVisible && snapshot.phase === 'playing';
  const bidActions = snapshot.legal.filter((action) => action.type === 'bid');
  const passAction = snapshot.legal.find((action) => action.type === 'pass');
  const hintAction = snapshot.legal.find((action) => action.type === 'hint');
  const playAction = snapshot.legal.find((action) => action.type === 'play');

  const bottom = (snapshot.extra['publicBottom'] ?? '').split(',').map(Number).filter(Number.isFinite);
  const last = (snapshot.extra['lastCards'] ?? '').split(',').map(Number).filter(Number.isFinite);
  const landlord = Number.parseInt(snapshot.extra['landlord'] ?? '-1', 10);

  const grouped = useMemo(() => sortHand(hand), [handKey]); // eslint-disable-line react-hooks/exhaustive-deps -- 同上

  const toggle = (card: number) => {
    if (!actionable) return;
    setSelected((current) => (current.includes(card) ? current.filter((value) => value !== card) : [...current, card]));
  };

  const play = () => {
    if (!actionable) return;
    if (selected.length === 0) { onFeedback('请先点选手牌，再出牌。'); return; }
    if (!selected.every((card) => hand.includes(card))) { onFeedback('所选牌不在手牌中。'); return; }
    onAction(viewer, { type: 'play', values: [...selected].sort((a, b) => a - b), text: '' });
    setSelected([]);
  };

  const useHint = () => {
    if (playAction) {
      setSelected([...playAction.values]);
      onFeedback('已选中推荐牌型；可调整后出牌。');
    } else if (hintAction) {
      onAction(viewer, hintAction);
      onFeedback('当前没有可压过上家的牌。');
    } else {
      onFeedback('当前没有可用的提示。');
    }
  };

  return (
    <div className="cards-panel">
      <div className="cards-meta">
        <span>底牌：{bottom.length ? bottom.map(cardLabel).join('  ') : '叫分结束后公开'}</span>
        <span>上手：{last.length ? last.map(cardLabel).join('  ') : '自由领出'}</span>
        <span>
          叫分 {snapshot.extra['bid'] ?? '0'} · 倍数 {snapshot.extra['multiplier'] ?? '1'}
          {landlord >= 0 ? ` · 地主 ${landlord + 1} 号` : ''}
        </span>
      </div>

      <h3 className="hand-title">
        {handVisible
          ? `${viewer + 1} 号的手牌 · 点按多选（已选 ${selected.length} 张）`
          : localSeats.length > 1
            ? '手牌已遮挡，请交给下一位玩家'
            : '手牌暂不可见'}
      </h3>

      {handVisible ? (
        <div className="hand-grid">
          {grouped.map((card) => {
            const chosen = selected.includes(card);
            return (
              <button
                key={card}
                type="button"
                className={`card-chip${chosen ? ' card-chip-chosen' : ''}${cardIsRed(card) ? ' card-chip-red' : ''}`}
                disabled={!actionable}
                aria-pressed={chosen}
                aria-label={`${cardLabel(card)}${chosen ? '，已选中' : ''}`}
                onClick={() => toggle(card)}
              >
                {cardLabel(card)}
              </button>
            );
          })}
        </div>
      ) : (
        <div className="hand-mask">出手前请先确认身份，避免替其他玩家查看手牌。</div>
      )}

      <div className="action-row">
        {snapshot.phase === 'playing' ? (
          <>
            <button type="button" className="btn btn-primary" disabled={!actionable} onClick={play}>出选中牌</button>
            <button type="button" className="btn btn-ghost" disabled={!actionable} onClick={() => setSelected([])}>清空选择</button>
            <button type="button" className="btn btn-ghost" disabled={!actionable} onClick={useHint}>提示</button>
            {passAction ? (
              <button type="button" className="btn btn-ghost" disabled={!actionable} onClick={() => onAction(viewer, passAction)}>
                {actionLabel('doudizhu', passAction, snapshot.width)}
              </button>
            ) : null}
          </>
        ) : null}
        {snapshot.phase === 'bidding' && bidActions.length > 0 ? (
          bidActions.map((action) => (
            <button
              key={`bid-${action.values[0] ?? 0}`}
              type="button"
              className={`btn ${action.values[0] === 3 ? 'btn-primary' : 'btn-ghost'}`}
              disabled={!canAct(snapshot, localSeats, viewer, viewer)}
              onClick={() => onAction(viewer, action)}
            >
              {actionLabel('doudizhu', action, snapshot.width)}
            </button>
          ))
        ) : null}
      </div>

      {snapshot.phase === 'bidding' ? (
        <p className="cards-tip">轮流叫分：3 分立即成为地主；三家都不叫则依次重新发牌，最多两次。</p>
      ) : null}
    </div>
  );
}

/** 手牌按点数、花色排序展示；不改动原始数据。 */
function sortHand(hand: number[]): number[] {
  return [...hand].sort((a, b) => rank(b) - rank(a) || b - a);
}

function rank(card: number): number {
  return card >= 52 ? card - 36 : Math.floor(card / 4) + 3;
}
