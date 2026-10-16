/** 应用外壳：底部导航（桌面为侧栏）、四个页面、本机对局与联机房间的全屏接管。 */
import { useCallback, useEffect, useMemo, useRef, useState, type ReactElement } from 'react';
import { findGame } from '../core/engine';
import type { GameInfo, GameSnapshot } from '../net/types';
import { GuestRoom } from '../modes/guestRoom';
import { HostRoom } from '../modes/hostRoom';
import { LocalMatch, type LocalMatchOptions } from '../modes/localMatch';
import { MatchView } from '../render/MatchView';
import { GamesIcon, Logo, NetIcon, PaletteIcon, SettingsIcon } from './Icons';
import { LobbyView, LocalSetupModal, type LocalConfig } from './Lobby';
import { AppearanceView, SettingsView } from './Look';
import { NetLobby } from './NetLobby';
import { RoomScreen } from './Room';
import { SettingsProvider } from './settings';
import { Button, StatusNote } from './Ui';
import { recordGame } from '../services/leaderboard';
import { LeaderboardView } from './LeaderboardView';

type Tab = 'games' | 'net' | 'rank' | 'look' | 'set';

const NAV: { id: Tab; label: string; icon: () => ReactElement }[] = [
  { id: 'games', label: '游戏', icon: GamesIcon },
  { id: 'net', label: '联机', icon: NetIcon },
  { id: 'rank', label: '排行', icon: GamesIcon },
  { id: 'look', label: '外观', icon: PaletteIcon },
  { id: 'set', label: '设置', icon: SettingsIcon },
];

export function App() {
  return (
    <SettingsProvider>
      <Shell />
    </SettingsProvider>
  );
}

function Shell() {
  const params = useMemo(() => new URLSearchParams(location.search), []);
  const lanHint = params.get('mode') === 'lan' || params.has('room');
  const [tab, setTab] = useState<Tab>(() => (params.get('mode') === 'lan' || params.has('room') || params.has('join') ? 'net' : 'games'));
  const [toast, setToast] = useState('');
  const [presetGame, setPresetGame] = useState<string | null>(null);
  const [setup, setSetup] = useState<{ game: GameInfo; error?: string } | null>(null);
  const [hostError, setHostError] = useState('');
  const [local, setLocal] = useState<{ match: LocalMatch; options: LocalMatchOptions; title: string } | null>(null);
  const [room, setRoom] = useState<{ client: HostRoom | GuestRoom } | null>(null);

  useEffect(() => {
    if (!toast) return;
    const timer = setTimeout(() => setToast(''), 6000);
    return () => clearTimeout(timer);
  }, [toast]);

  useEffect(() => {
    const current = local;
    return () => { current?.match.dispose(); };
  }, [local]);

  const exitLocal = useCallback((reason = '已结束本机对局') => {
    setLocal(null);
    setToast(reason);
  }, []);

  const startLocal = useCallback((game: GameInfo, config: LocalConfig) => {
    const options: LocalMatchOptions = {
      gameId: game.id,
      players: config.players,
      difficulty: config.difficulty,
      bots: config.bots,
      options: game.id === 'go' ? { boardSize: '9' } : {},
    };
    try {
      const match = new LocalMatch(options);
      setLocal({ match, options, title: game.title });
      setSetup(null);
    } catch (error) {
      setSetup({ game, error: error instanceof Error ? error.message : String(error) });
    }
  }, []);

  const restartLocal = useCallback(() => {
    if (!local) return;
    local.match.dispose();
    try {
      setLocal({ ...local, match: new LocalMatch(local.options) });
    } catch (error) {
      exitLocal(error instanceof Error ? error.message : '无法重新开始');
    }
  }, [local, exitLocal]);

  const openLocalSetup = useCallback((gameId: string) => {
    try {
      setSetup({ game: findGame(gameId) });
    } catch {
      setToast('未知游戏');
    }
  }, []);

  const exitRoom = useCallback((reason: string) => {
    setRoom(null);
    setToast(reason);
  }, []);

  if (local) {
    return (
      <LocalScreen
        match={local.match}
        title={local.title}
        onExit={() => exitLocal()}
        onRestart={restartLocal}
      />
    );
  }

  if (room) {
    return <RoomScreen client={room.client} onExit={exitRoom} />;
  }

  return (
    <div className="shell">
      <nav className="nav" aria-label="主导航">
        <div className="brand">
          <Logo />
          <div className="brand-copy">
            <span>PARTYGAME</span>
            <strong>同乐盒</strong>
          </div>
        </div>
        <div className="nav-items">
          {NAV.map((entry) => {
            const Icon = entry.icon;
            const active = tab === entry.id;
            return (
              <button
                key={entry.id}
                type="button"
                className={`nav-item${active ? ' nav-active' : ''}`}
                aria-current={active ? 'page' : undefined}
                onClick={() => setTab(entry.id)}
              >
                <Icon />
                <span>{entry.label}</span>
              </button>
            );
          })}
        </div>
      </nav>

      <main className="content">
        {tab === 'games' ? (
          <LobbyView
            onLocal={(game) => setSetup({ game })}
            onNet={(game) => { setPresetGame(game.id); setTab('net'); }}
          />
        ) : null}
        {tab === 'net' ? (
          <NetLobby
            presetGame={presetGame}
            lanHint={lanHint}
            hostError={hostError || undefined}
            onHost={(config) => {
              setHostError('');
              try {
                const client = new HostRoom(config.code, config.password, config.name);
                if (!client.chooseGame(config.gameId, config.players)) {
                  setToast('已创建房间，但预选游戏未生效，请在房间内重新选择。');
                }
                setRoom({ client });
              } catch (error) {
                setHostError(error instanceof Error ? error.message : '无法创建房间');
              }
            }}
            onJoin={(config) => {
              setRoom({ client: new GuestRoom({ code: config.code, password: config.password, name: config.name }) });
            }}
            onLanJoin={(config) => {
              setRoom({
                client: new GuestRoom({
                  code: config.code,
                  password: config.password,
                  name: config.name,
                  lanUrl: config.url,
                }),
              });
            }}
            onLocalSetup={openLocalSetup}
          />
        ) : null}
        {tab === 'rank' ? <LeaderboardView /> : null}
        {tab === 'look' ? <AppearanceView /> : null}
        {tab === 'set' ? <SettingsView /> : null}
      </main>

      {setup ? (
        <LocalSetupModal
          game={setup.game}
          error={setup.error}
          onClose={() => setSetup(null)}
          onStart={(config) => startLocal(setup.game, config)}
        />
      ) : null}

      {toast ? (
        <div className="toast" role="status">
          {toast}
          <button type="button" className="icon-btn" aria-label="关闭提示" onClick={() => setToast('')}>×</button>
        </div>
      ) : null}
    </div>
  );
}

function LocalScreen({
  match, title, onExit, onRestart,
}: {
  match: LocalMatch;
  title: string;
  onExit: () => void;
  onRestart: () => void;
}) {
  const [snapshot, setSnapshot] = useState<GameSnapshot | null>(null);
  const [notice, setNotice] = useState('');
  const recorded = useRef(false);

  useEffect(() => {
    recorded.current = false;
    const unsubscribe = match.subscribe(setSnapshot);
    match.start();
    return unsubscribe;
  }, [match]);

  useEffect(() => {
    if (!snapshot || snapshot.winner === null || recorded.current) return;
    recorded.current = true;
    const result = snapshot.winner === -1 ? 'draw' : snapshot.winner === match.viewer() ? 'win' : 'loss';
    void recordGame({
      game_id: match.gameId,
      player_name: '本机玩家',
      score: snapshot.scores[match.viewer()] ?? 0,
      result,
      metadata: { source: 'web-local', players: match.players },
    }).catch(() => undefined);
  }, [snapshot, match]);

  const localSeats = useMemo(() => Array.from({ length: match.players }, (_, seat) => seat), [match]);

  if (!snapshot) {
    return (
      <div className="page">
        <StatusNote>正在准备「{title}」…</StatusNote>
        <Button onClick={onExit}>返回大厅</Button>
      </div>
    );
  }

  const viewer = match.viewer();
  return (
    <MatchView
      snapshot={snapshot}
      viewer={viewer}
      localSeats={localSeats}
      handover={snapshot.turn < 0 || !match.bots.has(snapshot.turn)}
      notice={notice}
      onAction={(seat, action) => {
        if (!match.apply(seat, action) && snapshot.turn >= 0) setNotice('这一步未被接受，已同步最新局面。');
      }}
      onLeave={onExit}
      footer={(
        <>
          <Button compact onClick={onRestart}>重开一局</Button>
          <Button compact onClick={onExit}>返回大厅</Button>
        </>
      )}
    />
  );
}
