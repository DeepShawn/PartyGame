import { useEffect, useState } from 'react';
import { gameCatalog } from '../core/engine';
import { leaderboardEntries, type LeaderboardEntry } from '../services/leaderboard';
import { Badge, Card, SectionTitle, StatusNote } from './Ui';

export function LeaderboardView() {
  const [gameId, setGameId] = useState(gameCatalog[0]?.id ?? 'reaction');
  const [rows, setRows] = useState<LeaderboardEntry[]>([]);
  const [error, setError] = useState('');
  useEffect(() => {
    let alive = true;
    setError('');
    void leaderboardEntries(gameId).then((data) => { if (alive) setRows(data); }).catch((e: unknown) => { if (alive) setError(e instanceof Error ? e.message : '排行榜加载失败'); });
    return () => { alive = false; };
  }, [gameId]);
  return <div className="page">
    <SectionTitle title="排行榜" subtitle="每款游戏独立统计；正常对局自动保存，异常成绩会进入审核。" />
    <Card>
      <div className="leaderboard-picker" role="tablist" aria-label="选择排行榜游戏">{gameCatalog.map((g) => <button type="button" role="tab" aria-selected={gameId === g.id} className={`leaderboard-game${gameId === g.id ? ' leaderboard-game-active' : ''}`} onClick={() => setGameId(g.id)} key={g.id}><strong>{g.title}</strong><Badge tone={g.category === 'board' ? 'accent' : 'soft'}>{g.category === 'board' ? '棋类' : '小游戏'}</Badge></button>)}</div>
      {error ? <StatusNote tone="error">{error}</StatusNote> : null}
      {!error && rows.length === 0 ? <StatusNote>暂无已审核成绩，完成一局后会显示在这里。</StatusNote> : null}
      {rows.length > 0 ? <div className="table-wrap"><table><thead><tr><th>#</th><th>玩家</th><th>场次</th><th>胜场</th><th>总分</th><th>最佳</th></tr></thead><tbody>{rows.map((row, index) => <tr key={`${row.player_id ?? row.player_name}-${index}`}><td>{index + 1}</td><td>{row.player_name}</td><td>{row.games_played}</td><td>{row.wins}</td><td>{row.total_score}</td><td>{row.best_score}</td></tr>)}</tbody></table></div> : null}
    </Card>
  </div>;
}
