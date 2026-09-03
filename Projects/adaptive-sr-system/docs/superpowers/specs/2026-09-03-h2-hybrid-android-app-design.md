# H2 Hybrid Android App — Design Spec

Date: 2026-09-03
Status: Approved (Sections 1–4 reviewed in chat)
Decision: **H2 — Supabase primary, GAS sidecar.** Single user, free-forever, no billing anywhere.

## Context

The Adaptive SR System today: Raindrop/Form/Flutter-app in → Google Tasks with secure review links → SM-2 rescheduling → Master → Archive, Sheets as DB, email + dashboard as observability. Timezone Asia/Kolkata. The user juggles ~4 apps and wants ONE native Kotlin Android app as the only daily surface.

Why H2 over pure GAS / pure Supabase / phone-only: Supabase gives real Postgres (kills column-index fragility), true push, PostgREST auto-API, multi-device future. GAS sidecar covers exactly Supabase's weaknesses — 7-day pause (daily keepalive ping), no free email (MailApp digest), you-own-OAuth for Tasks (invisible script-owner auth). Both free-forever, no billing attached.

## 1. Architecture + backend contract

**Topology:** Supabase Postgres is the sole system of record. GAS is a `service_role` sidecar (Raindrop ingest, Tasks mirror, digest, keepalive, cold-mirror). Android talks only to Supabase PostgREST/RPC — never to Sheets/Tasks directly, Raindrop direct from phone for read + tag-toggle only. Old GAS `MemArchiveState`/`Archive`/`SR_Config` Sheets and `SpacedRepetition.gs:processReview` / `TaskManager.gs` / `RaindropSync.gs` remain as fallback until H2 cutover.

### 1.1 Schema (canonical DDL)

```sql
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

create or replace function sm2_next_interval(prev int, n int, rating text)
returns int language sql immutable as $$
  select case
    when rating = 'MASTER' then 0
    when rating = 'RELEARN' then 1
    when n = 0 and rating = 'EASY' then 4
    when n = 0 and rating = 'GOOD' then 2
    when n = 0 and rating = 'HARD' then 1
    when rating = 'EASY' then round(prev * 2.5)::int
    when rating = 'GOOD' then round(prev * 2.0)::int
    when rating = 'HARD' then round(prev * 1.2)::int
    else greatest(prev, 1) end
$$;

create index if not exists cards_due_idx
  on cards (user_id, next_review_at) where suspended = false;
create index if not exists cards_status_idx on cards (user_id, status);
create index if not exists cards_raindrop_idx on cards (raindrop_id);
create index if not exists reviews_card_time_idx on reviews (card_id, reviewed_at desc);
create index if not exists reviews_user_time_idx on reviews (user_id, reviewed_at desc);
create index if not exists notif_queue_idx
  on notifications (status, scheduled_for) where status = 'QUEUED';

alter table profiles enable row level security;
alter table cards enable row level security;
alter table reviews enable row level security;
alter table sync_state enable row level security;
alter table notifications enable row level security;

create policy "own rows" on profiles      for all using (auth.uid() = user_id) with check (auth.uid() = user_id);
create policy "own rows" on cards         for all using (auth.uid() = user_id) with check (auth.uid() = user_id);
create policy "own rows" on reviews       for all using (auth.uid() = user_id) with check (auth.uid() = user_id);
create policy "own rows" on sync_state    for all using (auth.uid() = user_id) with check (auth.uid() = user_id);
create policy "own rows" on notifications for all using (auth.uid() = user_id) with check (auth.uid() = user_id);
```

- SM-2 constants centralized in `sm2_next_interval()`; tweak without touching app code. Client previews with the same constants for display only; server authoritative.
- No separate archive table — MASTERED is `cards.status='MASTERED', suspended=true, next_review_at=NULL`.
- Migration `MemArchiveState[0..11]` → `cards`: timestamp→created_at, title→title, link→link, source→source, raindropId→raindrop_id, collection→collection, taskId→task_id, taskListId→task_list_id, sessionId→session_id, reviewCount→review_count, interval→interval_days, lastRating→last_rating. Derived: status/suspended/next_review_at per rules in §1. Dedupe via `unique(raindrop_id)` + loader-level `(user_id,link)` for APP/FORM rows.

### 1.2 RPCs + PostgREST

- `process_review(p_card_id uuid DEFAULT NULL, p_session_id text DEFAULT NULL, p_rating text, p_idempotency_key text)` — one of card/session id required (session_id = legacy GAS compat). Atomic txn: `SELECT ... FOR UPDATE` → `sm2_next_interval()` → `UPDATE cards` + `INSERT reviews`. Replay with same idempotency key → `{alreadyProcessed:true, errorCode:'ALREADY_PROCESSED'}`.
- `list_items(p_filter 'due'|'all'|'mastered', p_query, p_cursor_due, p_cursor_id, p_limit)` — due = `suspended=false AND next_review_at<=now() ORDER BY next_review_at,id`; returns items + `dueDate/overdueDays/nextPreview` + `nextCursor/hasMore`.
- `get_item_detail(p_item_id)` — card + `history[]` from reviews ASC (replaces Task-notes history).
- `update_item`, `delete_item` (soft `status='DELETED'` default, hard=true deletes), `get_dashboard_stats` (active/due/mastered/masteryRate/ratings/hardTopics[5]). (Amendment 2026-09-03: uppercase `DELETED` to match the `cards_status_check` convention `NEW/REVIEW/RELEARN/MASTERED`; Plan 1 Task 4 widens the check accordingly.)
- Reads: direct PostgREST `GET /rest/v1/cards` + dashboard views allowed. Writes: RPC only.
- Envelope everywhere: `{success, data, errorCode, version:1}` with `INVALID_RATING | ALREADY_PROCESSED | RATE_LIMITED | NOT_FOUND`.

### 1.3 Sidecar contract

`SupabaseSidecar.gs`: `sbGet/sbPost/sbPatch` helpers, keys `SUPABASE_URL`/`SUPABASE_SERVICE_KEY` in Script Properties (never in sheet). Sidecar uses service_role (bypasses RLS), upserts by `raindrop_id`, writes task/session ids, appends `reviews`, updates `sync_state.gas_last_seen_at + raindrop_cursor`; never deletes.

## 2. Screens + navigation

4 tabs, Room is UI truth, never PostgREST directly. Root: `android/app/src/main/java/com/adaptivesr/`.

- **Today** ← EmailDigest + buildTaskNotes. `dueQueue(now) ORDER BY due_at` from Room, overdue-first. Row: 5 rating buttons with preview text. `rateCard()` = optimistic Room update + `pendingSync=1` + flush. MASTER requires confirm → suspend path. Offline rows show "queued" badge. Share-target FAB entry here.
- **Library** ← MemArchiveState + RaindropSync. Tab1 All SR (`searchAll` over Room). Tab2 Raindrop (Retrofit direct: collections, search, `PUT raindrop/{id}` SR-tag toggle). Rule: phone never inserts cards directly — toggle-on enqueues insert deduped on `unique(raindrop_id)` (J4 authoritative); toggle-off never deletes the card.
- **Add** ← FormHandler + createSpacedRepetitionTask. FAB + `SEND/TEXT` share target via `ShareReceiverActivity` → `adaptivesr://add?text=`. Fully offline-capable.
- **Stats** ← Analytics + Dashboard.gs. Tiles active/due/mastered, mastery rate, ratings donut, hard-topics top-5, sync-status row (lastDuePull/lastFlush/pendingCount, DEGRADED flag).
- **Settings (only auth surface):** paste Supabase JWT + Raindrop token into Encrypted DataStore (`supabase_jwt`, `raindrop_token`, `fcm_token`); test buttons `SELECT v_stats LIMIT 1`, Raindrop `GET /user`. No login screen — single user, token provisioned once.

Stack: supabase-kt (postgrest + gotrue bearer) for Supabase, Retrofit for Raindrop REST only.

## 3. Sync/offline + errors + notifications

- **Offline model:** Room (`CardEntity` + `pendingSync,lastError`, index `due_at`; `SyncMeta`) is the single UI source. All Supabase reads → Room upsert; all writes → `pendingSync=1` → serial flush.
- **Workers:** `DuePullWorker` periodic 2h + expedited foreground pull on Today open. `ReviewFlushWorker` one-shot unique `review-flush` queue, backoff 10s/30s/2m max 5, `ALREADY_PROCESSED` clears flag, failure keeps flag + badge. `BackupCheckWorker` nightly 2am sets DEGRADED on drift. `ReminderWorker` daily 8am local notification from Room count.
- **Notifications (both paths):** pg_cron/Edge Function scans due → `notifications` QUEUED rows; webhook → FCM data `{due_count}` → `DuePushReceiver` → expedited pull + notification (Room is truth, payload never trusted). Local ReminderWorker is the no-FCM fallback. Digest keeps existing format (`Daily Digest - N items (M overdue)`, IST `en-IN/Asia/Kolkata` bucketing).
- **GAS sidecar jobs (Asia/Kolkata, `installH2Triggers()`):**
  - J1 `sbKeepAlivePing` daily 06:00 — cheap read, kills the 7-day pause.
  - J2 `sendDueDigestFromSupabase` daily 07:00 — due from Supabase, MailApp send, no per-row Tasks.get.
  - J3 `mirrorDueCardsToTasks` every 6h + 07:05 reconcile — watermark `TASKS_MIRROR_WATERMARK`, reuse `getOrCreateTaskList/buildTaskNotes/completeTask/smartThrottle`.
  - J4 `syncRaindropsToSupabase` 08:00+20:00 — reuse `fetchAllCollections/fetchBookmarksWithSR` verbatim (SR-tag rule, 429 → alert+retry), dedupe via `raindrop_id=in.(...)`, no Tasks here.
  - J5 cold-mirror `ColdMirrorLog` append-only hourly :35 + inline after review, watermark `COLD_MIRROR_LAST_AT`, never blocks review.
  - All jobs try/catch → existing `handleError` email.
- **Error mapping:** `INVALID_RATING` → bug-log + drop; `ALREADY_PROCESSED` → clear; `RATE_LIMITED`/timeout → keep queued + WorkManager retry; `NOT_FOUND` → drop + refresh. Never silent drop otherwise.

## 4. Build order + testing

Old GAS paths stay live as fallback at every step.

1. **Schema** — DDL + triggers. Test: RLS deny-anon, `sm2_next_interval` truth table, generated performance_score, due-query ordering, migration spot-check.
2. **RPCs** — process_review (session alias, idempotent replay, MASTER suspend, FOR UPDATE concurrency), list/detail/update/delete/dashboard. Test per §1 shapes incl. invalid-rating + rate-limit stubs. Fallback: legacy `processReview` still serves Sheets.
3. **Sidecar** — `SupabaseSidecar.gs` + J1→J5 + `installH2Triggers()`. Test: keepalive 2xx, digest empty/non-empty render, Tasks create/complete idempotency, Raindrop dedupe + 429 path, cold-mirror append + watermark. Fallback: existing jobs untouched until verified.
4. **Today + Add** — Room entities/DAO/`SrRepository`/`TodayViewModel`/rating buttons/MASTER confirm/queued badge; Add FAB + share-target offline insert. Test: airplane-mode rate → queued → flush → `ALREADY_PROCESSED` on replay.
5. **Library + Stats** — search tabs, SR-tag toggle (no-delete rule), stats tiles + DEGRADED footer, Settings token provisioning. Test: BackupCheckWorker drift, ReminderWorker/FCM with Room re-query, `SELECT v_stats LIMIT 1` smoke.

## Alternatives considered

- **A: Thin client over today's GAS API** — zero GAS changes but gaps remain (no sync-now, no full-list/search/edit/delete, polling-only). Rejected: doesn't truly replace all four apps.
- **Pure Supabase** — technically fits but adds pause mitigation + email vendor + Tasks OAuth ownership. Rejected as max-ops for single-user scale.
- **C: Middleware + FCM** — most robust/multi-user ready but a second backend to maintain. Rejected: overkill for one user.
- **H1 (GAS truth + Supabase mirror)** — good migration stepping stone, bad end state (dual-write confusion). H3 (phone truth + GAS backup) keeps Sheets fragility. H2 chosen as sweet spot.
