create table if not exists profiles (
  user_id uuid primary key references auth.users(id) on delete cascade,
  fcm_token text,
  timezone text not null default 'UTC',
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table if not exists cards (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  title text not null,
  link text,
  source text not null default 'APP' check (source in ('APP','RAINDROP','FORM')),
  raindrop_id bigint unique,
  collection text,
  task_id text,
  task_list_id text,
  session_id text,
  review_count int not null default 0 check (review_count >= 0),
  interval_days int not null default 0 check (interval_days >= 0),
  last_rating text check (last_rating in ('MASTER','EASY','GOOD','HARD','RELEARN')),
  status text not null default 'NEW'
    check (status in ('NEW','REVIEW','RELEARN','MASTERED')),
  suspended boolean not null default false,
  next_review_at timestamptz,
  last_reviewed_at timestamptz,
  performance_score int generated always as (
    case when review_count > 0
      then round(interval_days::numeric / review_count * 10)::int
      else 0 end
  ) stored,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table if not exists reviews (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  card_id uuid not null references cards(id) on delete cascade,
  rating text not null check (rating in ('MASTER','EASY','GOOD','HARD','RELEARN')),
  prev_interval int not null,
  new_interval int not null,
  review_count_after int not null,
  session_id text,
  idempotency_key text unique not null,
  reviewed_at timestamptz not null default now()
);

create table if not exists sync_state (
  user_id uuid primary key references auth.users(id) on delete cascade,
  raindrop_last_sync_at timestamptz,
  raindrop_cursor bigint default 0,
  sheets_migrated_upto_row int default 0,
  gas_last_seen_at timestamptz,
  updated_at timestamptz not null default now()
);

create table if not exists notifications (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  card_id uuid references cards(id) on delete set null,
  type text not null default 'REVIEW_DUE'
    check (type in ('REVIEW_DUE','SYNC_ERROR','STREAK')),
  payload jsonb not null default '{}',
  status text not null default 'QUEUED'
    check (status in ('QUEUED','SENT','FAILED')),
  attempts int not null default 0,
  last_error text,
  scheduled_for timestamptz not null default now(),
  sent_at timestamptz,
  created_at timestamptz not null default now()
);
