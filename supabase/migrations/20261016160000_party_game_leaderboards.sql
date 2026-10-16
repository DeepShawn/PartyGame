-- PartyGame: one leaderboard and immutable match records for every catalog game.
create table if not exists public.game_leaderboards (
  game_id text primary key,
  title text not null,
  category text not null,
  enabled boolean not null default true,
  created_at timestamptz not null default now()
);

create table if not exists public.game_records (
  id uuid primary key default gen_random_uuid(),
  game_id text not null references public.game_leaderboards(game_id),
  player_id uuid references auth.users(id) on delete set null,
  player_name text not null check (char_length(player_name) between 1 and 32),
  room_id text,
  score integer not null default 0 check (score between -1000000 and 1000000),
  result text not null check (result in ('win','draw','loss','score','quit')),
  duration_ms bigint not null default 0 check (duration_ms between 0 and 86400000),
  moves jsonb not null default '[]'::jsonb,
  metadata jsonb not null default '{}'::jsonb,
  status text not null default 'approved' check (status in ('pending','approved','rejected')),
  risk_score integer not null default 0 check (risk_score between 0 and 100),
  risk_flags text[] not null default '{}'::text[],
  reviewed_by uuid references auth.users(id) on delete set null,
  reviewed_at timestamptz,
  created_at timestamptz not null default now()
);

create index if not exists game_records_game_created_idx on public.game_records(game_id, created_at desc);
create index if not exists game_records_game_score_idx on public.game_records(game_id, score desc);
create index if not exists game_records_player_idx on public.game_records(player_id, created_at desc);

insert into public.game_leaderboards(game_id, title, category) values
  ('reaction','反应抢按','arcade'), ('color','颜色陷阱','arcade'), ('math','心算抢答','arcade'),
  ('memory','记忆翻牌','arcade'), ('snake','贪吃蛇竞技','arcade'), ('tank','坦克乱斗','arcade'),
  ('bomber','炸弹人','arcade'), ('dodge','躲避竞技','arcade'), ('gomoku','五子棋','board'),
  ('xiangqi','中国象棋','board'), ('chess','国际象棋','board'), ('go','围棋','board'),
  ('reversi','黑白棋','board'), ('chinese_checkers','中国跳棋','board'), ('draughts','国际跳棋','board'),
  ('doudizhu','斗地主','cards')
on conflict (game_id) do update set title = excluded.title, category = excluded.category;

alter table public.game_leaderboards enable row level security;
alter table public.game_records enable row level security;

drop policy if exists game_leaderboards_public_read on public.game_leaderboards;
create policy game_leaderboards_public_read on public.game_leaderboards for select to anon, authenticated using (enabled or is_staff());

drop policy if exists game_leaderboards_staff_write on public.game_leaderboards;
create policy game_leaderboards_staff_write on public.game_leaderboards for all to authenticated using (is_staff()) with check (is_staff());

drop policy if exists game_records_public_read on public.game_records;
create policy game_records_public_read on public.game_records for select to anon, authenticated using (status = 'approved' or is_staff());

drop policy if exists game_records_submit on public.game_records;
create policy game_records_submit on public.game_records for insert to anon, authenticated with check (status = 'approved' and (player_id is null or player_id = auth.uid()));

drop policy if exists game_records_staff_update on public.game_records;
create policy game_records_staff_update on public.game_records for update to authenticated using (is_staff()) with check (is_staff());

drop policy if exists game_records_staff_delete on public.game_records;
create policy game_records_staff_delete on public.game_records for delete to authenticated using (is_staff());

create or replace view public.game_leaderboard_entries with (security_invoker = true) as
select
  r.game_id,
  r.player_id,
  max(r.player_name) as player_name,
  count(*)::int as games_played,
  count(*) filter (where r.result = 'win')::int as wins,
  count(*) filter (where r.result = 'draw')::int as draws,
  sum(r.score)::int as total_score,
  max(r.score)::int as best_score,
  max(r.created_at) as last_played
from public.game_records r
where r.status = 'approved'
group by r.game_id, r.player_id, case when r.player_id is null then lower(r.player_name) else null end;

grant select on public.game_leaderboards to anon, authenticated;
grant select, insert on public.game_records to anon, authenticated;
grant update, delete on public.game_records to authenticated;
grant select on public.game_leaderboard_entries to anon, authenticated;
