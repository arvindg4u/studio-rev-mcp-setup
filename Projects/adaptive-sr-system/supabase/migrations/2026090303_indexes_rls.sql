create index if not exists cards_due_idx on cards (user_id, next_review_at) where suspended = false;
create index if not exists cards_status_idx on cards (user_id, status);
create index if not exists cards_raindrop_idx on cards (raindrop_id);
create index if not exists reviews_card_time_idx on reviews (card_id, reviewed_at desc);
create index if not exists reviews_user_time_idx on reviews (user_id, reviewed_at desc);
create index if not exists notif_queue_idx on notifications (status, scheduled_for) where status = 'QUEUED';

alter table profiles enable row level security;
alter table cards enable row level security;
alter table reviews enable row level security;
alter table sync_state enable row level security;
alter table notifications enable row level security;

drop policy if exists "own rows" on profiles;
drop policy if exists "own rows" on cards;
drop policy if exists "own rows" on reviews;
drop policy if exists "own rows" on sync_state;
drop policy if exists "own rows" on notifications;

create policy "own rows" on profiles      for all using (auth.uid() = user_id) with check (auth.uid() = user_id);
create policy "own rows" on cards         for all using (auth.uid() = user_id) with check (auth.uid() = user_id);
create policy "own rows" on reviews       for all using (auth.uid() = user_id) with check (auth.uid() = user_id);
create policy "own rows" on sync_state    for all using (auth.uid() = user_id) with check (auth.uid() = user_id);
create policy "own rows" on notifications for all using (auth.uid() = user_id) with check (auth.uid() = user_id);
