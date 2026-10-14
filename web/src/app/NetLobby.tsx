/** 联机大厅：公网房间（WebRTC）、局域网大厅（同源 WebSocket）与本机同屏三种入口。 */
import { useEffect, useMemo, useState } from 'react';
import { gameCatalog } from '../core/engine';
import type { GameInfo } from '../net/types';
import { loadName, normalizeCode, randomPassword, randomRoomCode, saveName } from './local';
import { Badge, Button, Card, SectionTitle, Segmented, StatusNote, TextField } from './Ui';

export interface NetLobbyProps {
  presetGame: string | null;
  lanHint: boolean;
  hostError?: string;
  onHost: (config: { code: string; password: string; name: string; gameId: string; players: number }) => void;
  onJoin: (config: { code: string; password: string; name: string }) => void;
  onLanJoin: (config: { code: string; url: string; password: string; name: string }) => void;
  onLocalSetup: (gameId: string) => void;
}

const GAME_OPTIONS = ['街机', '棋盘', '棋牌'];

export function NetLobby({ presetGame, lanHint, hostError, onHost, onJoin, onLanJoin, onLocalSetup }: NetLobbyProps) {
  const [name, setName] = useState(() => loadName());
  const [code, setCode] = useState(() => randomRoomCode());
  const [hostPassword, setHostPassword] = useState(() => randomPassword());
  const [hostGame, setHostGame] = useState(presetGame ?? 'gomoku');
  const [hostPlayers, setHostPlayers] = useState(() => defaultPlayers(presetGame ?? 'gomoku'));

  const [joinCode, setJoinCode] = useState(() => new URLSearchParams(location.search).get('join')?.toUpperCase() ?? '');
  const [joinPassword, setJoinPassword] = useState('');

  const [lanUrl, setLanUrl] = useState(() => (location.host ? `${location.protocol === 'https:' ? 'wss:' : 'ws:'}//${location.host}/room` : ''));
  const [lanCode, setLanCode] = useState(() => {
    const params = new URLSearchParams(location.search);
    return normalizeCode(params.get('room') ?? params.get('join') ?? '');
  });
  const [lanPassword, setLanPassword] = useState('');

  const [localGame, setLocalGame] = useState(presetGame ?? 'gomoku');

  useEffect(() => { saveName(name); }, [name]);
  useEffect(() => { if (presetGame) { setHostGame(presetGame); setHostPlayers(defaultPlayers(presetGame)); setLocalGame(presetGame); } }, [presetGame]);

  const hostInfo = useMemo(() => gameCatalog.find((game) => game.id === hostGame) ?? gameCatalog[0], [hostGame]);
  const trimmedName = name.trim() || '玩家';

  const hostPlayerOptions: { value: number; label: string }[] = [];
  for (let count = hostInfo.minPlayers; count <= hostInfo.maxPlayers; count += 1) {
    hostPlayerOptions.push({ value: count, label: `${count} 人` });
  }

  return (
    <div className="page">
      <SectionTitle title="联机大厅" subtitle="三种方式：公网房间无需配置；局域网由安卓房主提供页面；本机同屏完全不联网。" />

      {lanHint ? (
        <StatusNote tone="success">已从安卓房主的局域网页面进入：房间码已自动填入，输入房主给的房间口令即可加入。</StatusNote>
      ) : null}
      {hostError ? <StatusNote tone="error">{hostError}</StatusNote> : null}

      <div className="net-grid">
        <Card>
          <h3 className="card-title">公网房间 · 创建</h3>
          <p className="card-hint">生成六位房间码并设置口令，把房间码与口令发给好友；连接经 WebRTC 点对点直连。</p>
          <TextField label="你的昵称" value={name} onChange={setName} placeholder="最多 20 字" maxLength={20} />
          <div className="code-row">
            <TextField label="房间码" value={code} onChange={(value) => setCode(normalizeCode(value))} maxLength={6} uppercase placeholder="6 位字母数字" />
            <Button compact onClick={() => setCode(randomRoomCode())} title="重新生成">换一个</Button>
          </div>
          <div className="code-row">
            <TextField label="房间口令" value={hostPassword} onChange={setHostPassword} maxLength={32} password placeholder="好友加入需要输入" />
            <Button compact onClick={() => setHostPassword(randomPassword())} title="随机四位数字">随机</Button>
          </div>
          <label className="field">
            <span className="field-label">预选游戏</span>
            <select className="field-input" value={hostGame} onChange={(event) => { setHostGame(event.target.value); setHostPlayers(defaultPlayers(event.target.value)); }}>
              {GAME_OPTIONS.map((category) => (
                <optgroup key={category} label={category}>
                  {gameCatalog.filter((game) => categoryFor(game) === category).map((game) => (
                    <option key={game.id} value={game.id}>{game.title}</option>
                  ))}
                </optgroup>
              ))}
            </select>
          </label>
          <Segmented label="人数" value={hostPlayers} options={hostPlayerOptions} onChange={setHostPlayers} />
          <Button
            variant="primary"
            block
            disabled={code.length !== 6 || hostPassword.trim().length === 0}
            onClick={() => onHost({ code, password: hostPassword.trim(), name: trimmedName, gameId: hostGame, players: hostPlayers })}
          >
            创建房间
          </Button>
        </Card>

        <Card>
          <h3 className="card-title">公网房间 · 加入</h3>
          <p className="card-hint">输入房主给的房间码与口令；口令错误会提示并断开，房间满员或已开局会拒绝加入。</p>
          <TextField label="房间码" value={joinCode} onChange={(value) => setJoinCode(normalizeCode(value))} maxLength={6} uppercase placeholder="例如 AB12CD" />
          <TextField label="房间口令" value={joinPassword} onChange={setJoinPassword} maxLength={32} password placeholder="区分大小写" />
          <TextField label="你的昵称" value={name} onChange={setName} placeholder="最多 20 字" maxLength={20} />
          <Button
            variant="primary"
            block
            disabled={joinCode.length !== 6 || joinPassword.length === 0}
            onClick={() => onJoin({ code: joinCode, password: joinPassword, name: trimmedName })}
          >
            加入房间
          </Button>
        </Card>

        <Card>
          <h3 className="card-title">局域网大厅</h3>
          <p className="card-hint">同一 Wi-Fi 下，安卓端房主开启局域网大厅后，用本页连接同源 WebSocket；口令与公网房间一致。</p>
          <TextField label="大厅地址" value={lanUrl} onChange={setLanUrl} placeholder="ws://192.168.1.4:8080/room" />
          <div className="action-row">
            <Button compact onClick={() => setLanUrl(location.host ? `${location.protocol === 'https:' ? 'wss:' : 'ws:'}//${location.host}/room` : '')}>使用本页地址</Button>
          </div>
          <TextField label="房间码" value={lanCode} onChange={(value) => setLanCode(normalizeCode(value))} maxLength={6} uppercase placeholder="房主页面上显示的六位房间码" />
          <TextField label="房间口令" value={lanPassword} onChange={setLanPassword} maxLength={32} password placeholder="向房主索取" />
          <TextField label="你的昵称" value={name} onChange={setName} placeholder="最多 20 字" maxLength={20} />
          <Button
            variant="primary"
            block
            disabled={!lanUrl.startsWith('ws') || lanPassword.length === 0 || lanCode.length !== 6}
            onClick={() => onLanJoin({ code: lanCode, url: lanUrl.trim(), password: lanPassword, name: trimmedName })}
          >
            加入局域网大厅
          </Button>
        </Card>

        <Card>
          <h3 className="card-title">本机同屏</h3>
          <p className="card-hint">把手机放在桌上，2～3 人轮流操作；斗地主换手时会遮屏保护手牌。</p>
          <label className="field">
            <span className="field-label">选择游戏</span>
            <select className="field-input" value={localGame} onChange={(event) => setLocalGame(event.target.value)}>
              {GAME_OPTIONS.map((category) => (
                <optgroup key={category} label={category}>
                  {gameCatalog.filter((game) => categoryFor(game) === category).map((game) => (
                    <option key={game.id} value={game.id}>{game.title}</option>
                  ))}
                </optgroup>
              ))}
            </select>
          </label>
          <div className="game-summary">
            {gameCatalog.filter((game) => game.id === localGame).map((game) => (
              <div key={game.id}>
                <div className="detail-meta">
                  <Badge>{game.minPlayers === game.maxPlayers ? `${game.minPlayers} 人` : `${game.minPlayers}–${game.maxPlayers} 人`}</Badge>
                  {game.realtime ? <Badge tone="accent">实时</Badge> : <Badge tone="muted">回合</Badge>}
                  {game.bot ? <Badge tone="soft">离线 AI</Badge> : <Badge tone="muted">需联机</Badge>}
                </div>
                <p className="card-hint">{game.subtitle}</p>
              </div>
            ))}
          </div>
          <Button variant="primary" block onClick={() => onLocalSetup(localGame)}>配置并开始</Button>
        </Card>
      </div>
    </div>
  );
}

function categoryFor(game: GameInfo): string {
  return game.category === 'arcade' ? '街机' : game.category === 'board' ? '棋盘' : '棋牌';
}

function defaultPlayers(gameId: string): number {
  const info = gameCatalog.find((game) => game.id === gameId);
  if (!info) return 2;
  return Math.min(info.maxPlayers, Math.max(info.minPlayers, 2));
}
