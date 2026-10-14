/** 房间界面：成员列表、房主开局控制、邀请二维码与对局接管。 */
import { useEffect, useState } from 'react';
import QRCode from 'qrcode';
import { findGame, gameCatalog } from '../core/engine';
import type { GameSnapshot, GameInfo } from '../net/types';
import { MatchView } from '../render/MatchView';
import type { HostRoom } from '../modes/hostRoom';
import type { GuestRoom } from '../modes/guestRoom';
import type { RoomState } from '../modes/room';
import { CopyIcon } from './Icons';
import { Badge, Button, Card, Modal, SectionTitle, Segmented, StatusNote } from './Ui';

type AnyRoom = HostRoom | GuestRoom;

export function RoomScreen({ client, onExit }: { client: AnyRoom; onExit: (reason: string) => void }) {
  const [state, setState] = useState<RoomState>({ snapshot: null, status: '', closed: null });
  const [difficulty, setDifficulty] = useState(1);
  const [invite, setInvite] = useState('');
  const [copied, setCopied] = useState('');
  const [confirmLeave, setConfirmLeave] = useState(false);

  useEffect(() => client.subscribe(setState), [client]);

  useEffect(() => {
    if (state.closed) onExit(state.closed);
  }, [state.closed, onExit]);

  const hostClient = client.role === 'host' ? client : null;
  const guestClient = client.role === 'guest' ? client : null;
  const snapshot = state.snapshot;

  useEffect(() => {
    const inviteUrl = hostClient
      ? `${location.origin}${location.pathname}?join=${encodeURIComponent(hostClient.code)}`
      : guestClient?.isLan ? location.href : '';
    if (!inviteUrl) return;
    let alive = true;
    void QRCode.toDataURL(inviteUrl, { margin: 1, width: 208, errorCorrectionLevel: 'M' })
      .then((data) => { if (alive) setInvite(data); })
      .catch(() => { if (alive) setInvite(''); });
    return () => { alive = false; };
  }, [hostClient, guestClient]);

  const copyText = (value: string, label: string) => {
    void navigator.clipboard?.writeText(value).then(
      () => { setCopied(label); setTimeout(() => setCopied(''), 2000); },
      () => setCopied('复制失败，请手动选择文本'),
    );
  };

  const leaveRoom = () => {
    setConfirmLeave(false);
    client.leave();
  };

  if (!snapshot) {
    return (
      <div className="page">
        <SectionTitle title="正在进入房间" subtitle="正在与房主建立加密连接，完成后会自动显示房间状态。" />
        {state.status ? <StatusNote>{state.status}</StatusNote> : null}
        <Button onClick={leaveRoom}>返回联机大厅</Button>
      </div>
    );
  }

  const info = findGame(snapshot.gameId);
  const yourSeat = snapshot.yourSeat;

  if (snapshot.phase === 'playing' && snapshot.game) {
    const game: GameSnapshot = snapshot.game;
    return (
      <MatchView
        snapshot={game}
        viewer={yourSeat}
        localSeats={yourSeat >= 0 ? [yourSeat] : []}
        members={snapshot.members}
        notice={snapshot.message || state.status}
        onAction={(_seat, action) => {
          if (hostClient) hostClient.applyHostAction(action);
          else guestClient?.action(action);
        }}
        onLeave={() => setConfirmLeave(true)}
        footer={hostClient ? (
          <>
            <Button compact onClick={() => hostClient.returnToLobby()}>返回大厅</Button>
            <Button compact onClick={() => hostClient.startMatch(difficulty)}>再来一局</Button>
          </>
        ) : (
          <p className="card-hint">等待房主决定下一局或返回大厅。</p>
        )}
      />
    );
  }

  const members = snapshot.members;
  const seated = members.filter((member) => member.seat >= 0);
  const spectators = members.filter((member) => member.seat < 0);
  const missing = snapshot.capacity - seated.length;
  const everyoneReady = seated.every((member) => member.ready && member.connected);
  const canStart = everyoneReady && (info.bot || missing === 0);
  const startHint = !everyoneReady ? '等待所有玩家准备'
    : missing > 0 && !info.bot ? `还需要 ${missing} 名玩家加入`
      : '';

  const playerOptions: { value: number; label: string }[] = [];
  for (let count = info.minPlayers; count <= info.maxPlayers; count += 1) playerOptions.push({ value: count, label: `${count} 人` });

  const ownMember = members.find((member) => member.seat === yourSeat);

  return (
    <div className="page">
      <SectionTitle
        title={hostClient ? '房间 · 你是房主' : '房间 · 等待房主'}
        subtitle={`房间码 ${snapshot.roomId} · 邀请好友输入房间码与口令即可加入`}
      />

      <Card>
        <div className="room-head">
          <div className="room-code">
            <span>房间码</span>
            <strong>{snapshot.roomId}</strong>
            <Button compact onClick={() => copyText(snapshot.roomId, '房间码已复制')} title="复制房间码"><CopyIcon /></Button>
          </div>
          {hostClient ? (
            <div className="room-password">
              <span>口令</span>
              <code>{hostClient.password}</code>
              <Button compact onClick={() => copyText(hostClient.password, '口令已复制')} title="复制口令"><CopyIcon /></Button>
            </div>
          ) : null}
          {invite ? (
            <figure className="invite">
              <img src={invite} alt="房间邀请二维码" width={104} height={104} />
              <figcaption>{hostClient ? '扫码打开本站并自动填入房间码' : '同一网络的朋友扫码打开本页'}</figcaption>
            </figure>
          ) : null}
        </div>
        {state.status ? <StatusNote>{state.status}</StatusNote> : null}
        {snapshot.message ? <StatusNote>{snapshot.message}</StatusNote> : null}
        {copied ? <StatusNote tone="success">{copied}</StatusNote> : null}
      </Card>

      <Card>
        <h3 className="card-title">席位</h3>
        <div className="member-list">
          {Array.from({ length: snapshot.capacity }, (_, seat) => {
            const member = members.find((entry) => entry.seat === seat);
            const ai = !member && snapshot.bots.includes(seat);
            return (
              <div key={seat} className={`member${member ? '' : ' member-empty'}`}>
                <span className="member-seat">{seat + 1}</span>
                <div className="member-body">
                  <strong>{member ? member.name : ai ? 'AI 补位' : '空位'}</strong>
                  <span>
                    {member
                      ? [member.host ? '房主' : member.ready ? '已准备' : '未准备', member.connected ? '' : '掉线中', seat === yourSeat ? '你' : ''].filter(Boolean).join(' · ')
                      : ai ? '开局后自动行动' : info.bot ? '开局时自动补 AI' : '等待玩家加入'}
                  </span>
                </div>
                {member?.host ? <Badge tone="accent">房主</Badge> : member?.ready ? <Badge tone="soft">已准备</Badge> : null}
              </div>
            );
          })}
        </div>
        {spectators.length > 0 ? (
          <p className="card-hint">观战：{spectators.map((member) => member.name).join('、')}</p>
        ) : null}
      </Card>

      {hostClient ? (
        <Card>
          <h3 className="card-title">房主控制</h3>
          <label className="field">
            <span className="field-label">房间游戏</span>
            <select
              className="field-input"
              value={snapshot.gameId}
              onChange={(event) => {
                const next = findGame(event.target.value);
                hostClient.chooseGame(next.id, Math.min(next.maxPlayers, Math.max(next.minPlayers, snapshot.capacity)));
              }}
            >
              {['arcade', 'board', 'cards'].map((category) => (
                <optgroup key={category} label={categoryLabel(category)}>
                  {gameCatalog.filter((game) => game.category === category).map((game) => (
                    <option key={game.id} value={game.id}>{game.title}</option>
                  ))}
                </optgroup>
              ))}
            </select>
          </label>
          <Segmented
            label="人数"
            value={snapshot.capacity}
            options={playerOptions}
            onChange={(value) => hostClient.chooseGame(snapshot.gameId, value)}
          />
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
          <p className="card-hint">{info.rules}</p>
          <div className="action-row">
            <Button variant="primary" disabled={!canStart} onClick={() => hostClient.startMatch(difficulty)}>开始对局</Button>
            <Button onClick={leaveRoom}>离开房间</Button>
          </div>
          {startHint ? <p className="card-hint">{startHint}</p> : null}
        </Card>
      ) : (
        <Card>
          <h3 className="card-title">准备</h3>
          <p className="card-hint">
            当前游戏：{info.title}（{formatPlayers(info)}）。房主开始后会自动进入对局。
          </p>
          <div className="action-row">
            <Button
              variant="primary"
              disabled={yourSeat < 0}
              onClick={() => guestClient?.ready(!(ownMember?.ready ?? false))}
            >
              {ownMember?.ready ? '取消准备' : '我准备好了'}
            </Button>
            <Button onClick={leaveRoom}>离开房间</Button>
          </div>
          {yourSeat < 0 ? <p className="card-hint">你当前是观战席位，等待有玩家离开后才能上场。</p> : null}
        </Card>
      )}

      {confirmLeave ? (
        <Modal
          title="离开房间？"
          subtitle={hostClient ? '房主离开会断开所有玩家并关闭房间。' : '离开后需要重新输入房间码和口令才能回来。'}
          onClose={() => setConfirmLeave(false)}
        >
          <div className="action-row">
            <Button variant="danger" onClick={leaveRoom}>确认离开</Button>
            <Button onClick={() => setConfirmLeave(false)}>继续对局</Button>
          </div>
        </Modal>
      ) : null}
    </div>
  );
}

function categoryLabel(category: string): string {
  return category === 'arcade' ? '街机' : category === 'board' ? '棋盘' : '棋牌';
}

export function formatPlayers(info: GameInfo): string {
  return info.minPlayers === info.maxPlayers ? `${info.minPlayers} 人` : `${info.minPlayers}–${info.maxPlayers} 人`;
}
