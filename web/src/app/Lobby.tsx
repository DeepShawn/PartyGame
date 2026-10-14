/** 游戏大厅：16 款游戏卡片、规则详情与开始入口（含本机同屏配置）。 */
import { useMemo, useState } from 'react';
import { gameCatalog } from '../core/engine';
import type { GameInfo } from '../net/types';
import { useSettings } from './settings';
import { GameIcon, Logo } from './Icons';
import { Badge, Button, Modal, SectionTitle, Segmented } from './Ui';

const CATEGORIES = [
  { id: 'all', label: '全部' },
  { id: 'arcade', label: '街机' },
  { id: 'board', label: '棋盘' },
  { id: 'cards', label: '棋牌' },
];

function playersLabel(info: GameInfo): string {
  return info.minPlayers === info.maxPlayers ? `${info.minPlayers} 人` : `${info.minPlayers}–${info.maxPlayers} 人`;
}

export function LobbyView({ onLocal, onNet }: { onLocal: (game: GameInfo) => void; onNet: (game: GameInfo) => void }) {
  const [category, setCategory] = useState('all');
  const [search, setSearch] = useState('');
  const [detail, setDetail] = useState<GameInfo | null>(null);

  const games = useMemo(() => {
    const keyword = search.trim().toLowerCase();
    return gameCatalog.filter((info) => (category === 'all' || info.category === category)
      && (!keyword || `${info.title}${info.subtitle}${info.rules}${info.id}`.toLowerCase().includes(keyword)));
  }, [category, search]);

  return (
    <div className="page">
      <SectionTitle title="游戏大厅" subtitle="2～3 人小游戏与棋牌，支持联机对局，也能本机同屏轮流操作。桌面端另有侧栏布局。" />
      <div className="lobby-tools">
        <div className="chips">
          {CATEGORIES.map((entry) => (
            <button
              key={entry.id}
              type="button"
              className={`chip${category === entry.id ? ' chip-active' : ''}`}
              aria-pressed={category === entry.id}
              onClick={() => setCategory(entry.id)}
            >
              {entry.label}
            </button>
          ))}
        </div>
        <input
          className="field-input search"
          type="search"
          value={search}
          placeholder="搜索游戏、规则关键词"
          aria-label="搜索游戏"
          onChange={(event) => setSearch(event.target.value)}
        />
      </div>

      <div className="game-grid">
        {games.map((info) => (
          <button key={info.id} type="button" className="game-card" onClick={() => setDetail(info)}>
            <GameIcon id={info.id} title={info.title} />
            <span className="game-cell">
              <strong>{info.title}</strong>
              <span className="game-subtitle">{info.subtitle}</span>
              <span className="game-tags">
                <Badge>{playersLabel(info)}</Badge>
                {info.realtime ? <Badge tone="accent">实时</Badge> : <Badge tone="muted">回合</Badge>}
                {info.bot ? <Badge tone="soft">离线 AI</Badge> : <Badge tone="muted">需联机</Badge>}
              </span>
            </span>
          </button>
        ))}
      </div>
      {games.length === 0 ? <p className="empty-hint">没有匹配的游戏，换个关键词试试。</p> : null}

      {detail ? (
        <Modal
          title={detail.title}
          subtitle={detail.subtitle}
          onClose={() => setDetail(null)}
          wide
        >
          <div className="detail-meta">
            <Badge>{playersLabel(detail)}</Badge>
            <Badge tone={detail.realtime ? 'accent' : 'muted'}>{detail.realtime ? '实时对局' : '回合制'}</Badge>
            <Badge tone={detail.bot ? 'soft' : 'muted'}>{detail.bot ? '支持离线 AI' : '暂不支持离线 AI'}</Badge>
            {detail.id === 'doudizhu' ? <Badge tone="danger">同机换手遮屏</Badge> : null}
          </div>
          <p className="rules-text">{detail.rules}</p>
          <div className="action-row">
            <Button variant="primary" onClick={() => { const game = detail; setDetail(null); onLocal(game); }}>本机开局</Button>
            <Button onClick={() => { const game = detail; setDetail(null); onNet(game); }}>联机房间</Button>
          </div>
        </Modal>
      ) : null}
    </div>
  );
}

export interface LocalConfig {
  players: number;
  bots: number[];
  difficulty: number;
}

export function LocalSetupModal({
  game, error, onClose, onStart,
}: {
  game: GameInfo;
  error?: string;
  onClose: () => void;
  onStart: (config: LocalConfig) => void;
}) {
  const { settings } = useSettings();
  const [players, setPlayers] = useState(game.minPlayers);
  const [bots, setBots] = useState<number[]>([]);
  const [difficulty, setDifficulty] = useState(settings.aiDifficulty);

  const toggleBot = (seat: number) => {
    setBots((current) => (current.includes(seat) ? current.filter((value) => value !== seat) : [...current, seat]));
  };

  const playerOptions: { value: number; label: string }[] = [];
  for (let count = game.minPlayers; count <= game.maxPlayers; count += 1) playerOptions.push({ value: count, label: `${count} 人` });

  return (
    <Modal title={`${game.title} · 本机同屏`} subtitle="同一台设备上轮流操作，不需要联网" onClose={onClose} wide>
      <Segmented label="人数" value={players} options={playerOptions} onChange={(value) => {
        setPlayers(value);
        setBots((current) => current.filter((seat) => seat < value));
      }} />
      <div className="seat-config">
        {Array.from({ length: players }, (_, seat) => (
          <div key={seat} className="seat-row">
            <span className="seat-name">席位 {seat + 1}</span>
            <Segmented
              value={bots.includes(seat) ? 'bot' : 'human'}
              options={[
                { value: 'human', label: '真人' },
                { value: 'bot', label: '电脑' },
              ]}
              onChange={(value) => {
                if (value === 'bot' && !game.webBot) return;
                toggleBot(seat);
              }}
            />
          </div>
        ))}
      </div>
      {!game.webBot ? (
        <p className="card-hint">
          {game.id === 'go'
            ? '围棋的离线 AI 需要安卓房主建立对局；本机对局请全部选择真人。'
            : '这款游戏暂未提供离线 AI，联机房间中可以由其他玩家补位。'}
        </p>
      ) : (
        <p className="card-hint">电脑席位会在自己回合自动行动；国际象棋使用浏览器内置 Stockfish，思考时间随难度增加。</p>
      )}
      <Segmented
        label="AI 难度"
        value={difficulty}
        options={[
          { value: 0, label: '轻松' },
          { value: 1, label: '标准' },
          { value: 2, label: '挑战' },
        ]}
        onChange={setDifficulty}
      />
      <div className="setup-notice">
        {game.id === 'doudizhu' ? <p className="card-hint"><Logo size={16} /> 三位真人同机时，每次换手都会遮屏，确认身份后才展示手牌。</p> : null}
        {error ? <p className="status-note status-error" role="alert">{error}</p> : null}
      </div>
      <div className="action-row">
        <Button variant="primary" onClick={() => onStart({ players, bots, difficulty })}>开始对局</Button>
        <Button onClick={onClose}>取消</Button>
      </div>
    </Modal>
  );
}
