import { supabase } from './supabase';
export { supabase } from './supabase';

export interface Leaderboard {
  game_id: string;
  title: string;
  category: string;
  enabled: boolean;
}

export interface LeaderboardEntry {
  game_id: string;
  player_id: string | null;
  player_name: string;
  games_played: number;
  wins: number;
  draws: number;
  total_score: number;
  best_score: number;
  last_played: string;
}

export interface GameRecordInput {
  game_id: string;
  player_id?: string | null;
  player_name: string;
  room_id?: string | null;
  score: number;
  result: 'win' | 'draw' | 'loss' | 'score' | 'quit';
  duration_ms?: number;
  moves?: unknown[];
  metadata?: Record<string, unknown>;
}

function reviewDecision(input: GameRecordInput): { status: 'approved' | 'pending'; risk_score: number; risk_flags: string[] } {
  const flags: string[] = [];
  if (input.duration_ms !== undefined && input.duration_ms < 250 && input.result !== 'quit') flags.push('impossibly_fast');
  if (input.score > 1000000 || input.score < -1000000) flags.push('score_out_of_range');
  const risk_score = Math.min(100, flags.length * 50);
  return { status: risk_score >= 50 ? 'pending' : 'approved', risk_score, risk_flags: flags };
}

export async function listLeaderboards(): Promise<Leaderboard[]> {
  const { data, error } = await supabase.from('game_leaderboards').select('game_id,title,category,enabled').eq('enabled', true).order('category').order('title');
  if (error) throw error;
  return data ?? [];
}

export async function leaderboardEntries(gameId: string): Promise<LeaderboardEntry[]> {
  const { data, error } = await supabase.from('game_leaderboard_entries').select('*').eq('game_id', gameId).order('total_score', { ascending: false }).limit(100);
  if (error) throw error;
  return data ?? [];
}

export async function recordGame(input: GameRecordInput): Promise<void> {
  const decision = reviewDecision(input);
  const { data: user } = await supabase.auth.getUser();
  const { error } = await supabase.from('game_records').insert({
    ...input,
    player_id: input.player_id ?? user.user?.id ?? null,
    duration_ms: input.duration_ms ?? 0,
    moves: input.moves ?? [],
    metadata: input.metadata ?? {},
    ...decision,
  });
  if (error) throw error;
}

export async function adminSignIn(email: string, password: string): Promise<void> {
  const { error } = await supabase.auth.signInWithPassword({ email, password });
  if (error) throw error;
}

export async function adminSignOut(): Promise<void> {
  await supabase.auth.signOut();
}

export async function adminRecords(gameId?: string): Promise<Record<string, unknown>[]> {
  let query = supabase.from('game_records').select('*').order('created_at', { ascending: false }).limit(500);
  if (gameId) query = query.eq('game_id', gameId);
  const { data, error } = await query;
  if (error) throw error;
  return data ?? [];
}

export async function moderateRecord(id: string, status: 'approved' | 'rejected'): Promise<void> {
  const { data: user } = await supabase.auth.getUser();
  const { error } = await supabase.from('game_records').update({ status, reviewed_by: user.user?.id ?? null, reviewed_at: new Date().toISOString() }).eq('id', id);
  if (error) throw error;
}
