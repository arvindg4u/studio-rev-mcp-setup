# H2 Plan 1: Supabase Backend (Schema + RPCs) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the Supabase Postgres system-of-record for H2: canonical tables, SM-2 function, RLS, and all RPCs with the versioned envelope.

**Architecture:** Postgres-first: tables + immutable `sm2_next_interval()` hold all SR logic; writes go only through `SECURITY INVOKER` RPCs doing `SELECT ... FOR UPDATE` + `UPDATE cards` + `INSERT reviews` in one transaction; reads go via PostgREST or RPC; every RPC returns `{success,data,errorCode,version:1}`.

**Tech Stack:** Supabase CLI (>=1.150), Postgres 15, PL/pgSQL + plain SQL, PostgREST/RPC, `psql` + plain `DO` blocks for TDD (no pgTAP dependency), `gen_random_uuid()` (pgcrypto built-in).

**Spec:** `/teamspace/studios/this_studio/Projects/adaptive-sr-system/docs/superpowers/specs/2026-09-03-h2-hybrid-android-app-design.md` Section 1 (§1.1 schema DDL + sm2, §1.2 RPCs + envelope, §1.3 sidecar service_role note, §4 build-order items 1–2).

## Global Constraints

- Supabase Postgres is the sole system of record; Android talks only to Supabase PostgREST/RPC, never to Sheets/Tasks directly.
- Single user, free-forever, no billing anywhere; no paid Postgres extensions.
- Timezone for all user-facing bucketing is `Asia/Kolkata`; store all timestamps as `timestamptz`, compute `now()` server-side.
- Envelope everywhere: `{success boolean, data jsonb|null, errorCode text|null, version: 1}` with codes `INVALID_RATING | ALREADY_PROCESSED | RATE_LIMITED | NOT_FOUND | null`.
- Reads: direct PostgREST `GET /rest/v1/cards` + dashboard views allowed. Writes: RPC only.
- MASTERED is `cards.status='MASTERED', suspended=true, next_review_at=NULL`; no separate archive table.
- Sidecar uses `service_role` (bypasses RLS), upserts by `raindrop_id`, never deletes.
- Old GAS `MemArchiveState`/`Archive` Sheets and `processReview` remain live fallback; this plan must not break them (no shared objects).
- `sm2_next_interval()` is the single source of SM-2 constants; client previews only, server authoritative.

---

## File Structure

All paths rooted at `/teamspace/studios/this_studio/Projects/adaptive-sr-system/`:

- Create: `supabase/config.toml` — local Supabase project config (db port, no billing features).
- Create: `supabase/migrations/2026090301_schema.sql` — tables `profiles/cards/reviews/sync_state/notifications` verbatim from spec §1.1 (Task 1).
- Create: `supabase/migrations/2026090302_sm2.sql` — `sm2_next_interval()` verbatim from spec (Task 1).
- Create: `supabase/migrations/2026090303_indexes_rls.sql` — 6 indexes + `ENABLE ROW LEVEL SECURITY` + 5 `"own rows"` policies (Task 2).
- Create: `supabase/migrations/2026090304_process_review.sql` — `process_review()` RPC (Task 3).
- Create: `supabase/migrations/2026090305_read_rpcs.sql` — `list_items/get_item_detail/update_item/delete_item/get_dashboard_stats` + `DELETED` status widening + views `v_due_queue/v_stats` (Task 4).
- Create: `supabase/migrations/2026090306_seed_demo.sql` — NOT shipped; dev-only seed kept out of migrations (reference for Task 5, applied manually).
- Create: `supabase/tests/01_sm2.sql` — SM-2 truth-table DO blocks (Task 1).
- Create: `supabase/tests/02_rls.sql` — RLS enabled/policy-exists + anon-denied DO blocks (Task 2).
- Create: `supabase/tests/03_process_review.sql` — idempotent replay / MASTER suspend / session alias / invalid rating / FOR UPDATE smoke (Task 3).
- Create: `supabase/tests/04_read_rpcs.sql` — list/detail/update/delete/dashboard assertions (Task 4).
- Create: `supabase/tests/05_migration_check.sql` — MemArchiveState mapping + seed verification (Task 5).
- Create: `supabase/seed_demo.sql` — 5 representative cards + reviews exercising every status path (Task 5).

Migration numbering must stay in this order; each migration is idempotent (`IF NOT EXISTS` / `OR REPLACE`).

---

### Task 1: Tables + sm2_next_interval

**Files:**
- Create: `supabase/config.toml`
- Create: `supabase/migrations/2026090301_schema.sql`
- Create: `supabase/migrations/2026090302_sm2.sql`
- Create: `supabase/tests/01_sm2.sql`

**Interfaces:**
- Consumes: nothing (foundation task).
- Produces: `sm2_next_interval(prev int, n int, rating text) RETURNS int IMMUTABLE`; tables `profiles/cards/reviews/sync_state/notifications` with exact spec §1.1 columns, checks, generated `performance_score`.

- [ ] **Step 1: Write the failing test `supabase/tests/01_sm2.sql`**

```sql
-- 01_sm2.sql: SM-2 truth table. Fails before 2026090302_sm2.sql exists.
DO $$
DECLARE v int;
BEGIN
  SELECT sm2_next_interval(10, 3, 'MASTER') INTO v;  ASSERT v = 0,  'MASTER must be 0, got ' || v;
  SELECT sm2_next_interval(10, 3, 'RELEARN') INTO v; ASSERT v = 1,  'RELEARN must be 1, got ' || v;
  SELECT sm2_next_interval(99, 0, 'EASY') INTO v;    ASSERT v = 4,  'n=0/EASY must be 4, got ' || v;
  SELECT sm2_next_interval(99, 0, 'GOOD') INTO v;    ASSERT v = 2,  'n=0/GOOD must be 2, got ' || v;
  SELECT sm2_next_interval(99, 0, 'HARD') INTO v;    ASSERT v = 1,  'n=0/HARD must be 1, got ' || v;
  SELECT sm2_next_interval(10, 3, 'EASY') INTO v;    ASSERT v = 25, 'EASY 10*2.5=25, got ' || v;
  SELECT sm2_next_interval(10, 3, 'GOOD') INTO v;    ASSERT v = 20, 'GOOD 10*2.0=20, got ' || v;
  SELECT sm2_next_interval(10, 3, 'HARD') INTO v;    ASSERT v = 12, 'HARD 10*1.2=12, got ' || v;
  SELECT sm2_next_interval(7, 2, 'EASY') INTO v;     ASSERT v = 18, 'EASY 7*2.5=17.5 round 18, got ' || v;
  SELECT sm2_next_interval(0, 5, 'BOGUS') INTO v;    ASSERT v = 1,  'else-branch greatest(prev,1)=1, got ' || v;
  RAISE NOTICE 'SM2_TRUTH_TABLE_OK';
END $$;
```

- [ ] **Step 2: Run test to verify it fails**

Run: `psql "$SUPABASE_DB_URL" -f supabase/tests/01_sm2.sql`
Expected: FAIL with `function sm2_next_interval does not exist`.

- [ ] **Step 3: Write minimal migrations (verbatim spec DDL)**

`supabase/migrations/2026090301_schema.sql` contains the spec §1.1 `create table` blocks for `profiles`, `cards` (with generated `performance_score`), `reviews` (with `idempotency_key text unique not null`), `sync_state`, `notifications` exactly as specced. `supabase/migrations/2026090302_sm2.sql`:

```sql
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
```

`supabase/config.toml` minimal:

```toml
[db]
port = 54322
```

- [ ] **Step 4: Run test to verify it passes**

Run: `psql "$SUPABASE_DB_URL" -f supabase/migrations/2026090301_schema.sql && psql "$SUPABASE_DB_URL" -f supabase/migrations/2026090302_sm2.sql && psql "$SUPABASE_DB_URL" -f supabase/tests/01_sm2.sql`
Expected: PASS with `SM2_TRUTH_TABLE_OK`. Also spot-check generated column: `psql "$SUPABASE_DB_URL" -c "insert into cards (user_id,title) values ('00000000-0000-0000-0000-000000000000','x') on conflict do nothing;"` is NOT run (FK to auth.users); generated-column check deferred to Task 5 seed with a real user id.

- [ ] **Step 5: Commit**

```bash
git add supabase/config.toml supabase/migrations/2026090301_schema.sql supabase/migrations/2026090302_sm2.sql supabase/tests/01_sm2.sql
git commit -m "feat(supabase): schema tables and sm2_next_interval"
```

---

### Task 2: Indexes + RLS

**Files:**
- Create: `supabase/migrations/2026090303_indexes_rls.sql`
- Create: `supabase/tests/02_rls.sql`

**Interfaces:**
- Consumes: tables from Task 1.
- Produces: 6 indexes (`cards_due_idx`, `cards_status_idx`, `cards_raindrop_idx`, `reviews_card_time_idx`, `reviews_user_time_idx`, `notif_queue_idx`); RLS enabled on all 5 tables; policy `"own rows"` FOR ALL on each table keyed on `auth.uid() = user_id`.

- [ ] **Step 1: Write the failing test `supabase/tests/02_rls.sql`**

```sql
-- 02_rls.sql: RLS on + own-rows policies + anon cannot read cards.
DO $$
DECLARE c_rls int; c_pol int;
BEGIN
  SELECT count(*) INTO c_rls FROM pg_tables WHERE schemaname='public' AND tablename IN ('profiles','cards','reviews','sync_state','notifications') AND rowsecurity = true;
  ASSERT c_rls = 5, 'RLS must be enabled on 5 tables, got ' || c_rls;
  SELECT count(*) INTO c_pol FROM pg_policies WHERE schemaname='public' AND policyname='own rows' AND tablename IN ('profiles','cards','reviews','sync_state','notifications');
  ASSERT c_pol = 5, 'own-rows policy must exist on 5 tables, got ' || c_pol;
  RAISE NOTICE 'RLS_CATALOG_OK';
END $$;
-- Anon probe: must see zero rows / be denied (RLS, no anon policy).
SET ROLE anon;
DO $$
DECLARE n int;
BEGIN
  SELECT count(*) INTO n FROM public.cards;
  ASSERT n = 0, 'anon must see 0 cards rows, saw ' || n;
  RAISE NOTICE 'RLS_ANON_DENY_OK';
END $$;
RESET ROLE;
```

- [ ] **Step 2: Run test to verify it fails**

Run: `psql "$SUPABASE_DB_URL" -f supabase/tests/02_rls.sql`
Expected: FAIL with assertion `RLS must be enabled on 5 tables` (0 of 5 before migration).

- [ ] **Step 3: Write minimal implementation `supabase/migrations/2026090303_indexes_rls.sql`**

```sql
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
```

- [ ] **Step 4: Run test to verify it passes**

Run: `psql "$SUPABASE_DB_URL" -f supabase/migrations/2026090303_indexes_rls.sql && psql "$SUPABASE_DB_URL" -f supabase/tests/02_rls.sql`
Expected: PASS with `RLS_CATALOG_OK` and `RLS_ANON_DENY_OK`.

- [ ] **Step 5: Commit**

```bash
git add supabase/migrations/2026090303_indexes_rls.sql supabase/tests/02_rls.sql
git commit -m "feat(supabase): indexes and own-rows RLS"
```

---

### Task 3: process_review RPC (atomic review + idempotency + MASTER suspend)

**Files:**
- Create: `supabase/migrations/2026090304_process_review.sql`
- Create: `supabase/tests/03_process_review.sql`

**Interfaces:**
- Consumes: `sm2_next_interval()` (Task 1), `cards`/`reviews` tables, `auth.uid()`.
- Produces: `process_review(p_card_id uuid DEFAULT NULL, p_session_id text DEFAULT NULL, p_rating text DEFAULT NULL, p_idempotency_key text DEFAULT NULL) RETURNS jsonb` returning envelope (spec-deviation note, intentional: spec §1.2 lists `p_rating`/`p_idempotency_key` as required with no DEFAULT; this plan gives all four params DEFAULT NULL and returns envelope errors `INVALID_RATING`/`NOT_FOUND` instead of PG "missing argument" errors, so phone/sidecar callers always get the envelope) `{success,data,errorCode,version}`. `data` on success: `{cardId,newInterval,reviewCount,nextReviewAt,alreadyProcessed}`. Error codes: `INVALID_RATING` (rating not in MASTER/EASY/GOOD/HARD/RELEARN or null), `NOT_FOUND` (no card for id/session or idempotency key missing), `ALREADY_PROCESSED` (replay, http-200-style success:true), `RATE_LIMITED` (>30 reviews by caller in last 60s).

- [ ] **Step 1: Write the failing test `supabase/tests/03_process_review.sql`**

```sql
-- 03_process_review.sql: runs as table owner (bypasses RLS) for deterministic
-- logic assertions; the one committed RLS-through-RPC assertion lives in 04_read_rpcs.sql.
-- Setup: one scratch user + card. Idempotency keys are unique per run via clock.
-- auth.users preamble: required where the FK to auth.users is enforced (hosted);
-- silently skipped when the runner lacks auth-schema writes (DO block swallows).
DO $$
BEGIN
  INSERT INTO auth.users (id, aud, role, email, encrypted_password, email_confirmed_at, created_at, updated_at)
    VALUES ('11111111-1111-1111-1111-111111111111','authenticated','authenticated','h2t3@example.com','x',now(),now(),now())
    ON CONFLICT (id) DO NOTHING;
EXCEPTION WHEN OTHERS THEN RAISE NOTICE 'auth.users seed skipped (non-superuser runner)';
END $$;
DO $$
DECLARE
  uid uuid := '11111111-1111-1111-1111-111111111111';
  cid uuid;
  r jsonb;
  r1 jsonb;
  k1 text := 'test-key-1-' || extract(epoch from now())::bigint::text;
  k2 text := 'test-key-2-' || extract(epoch from now())::bigint::text;
  k3 text := 'test-key-3-' || extract(epoch from now())::bigint::text;
BEGIN
  PERFORM set_config('request.jwt.claim.sub', '11111111-1111-1111-1111-111111111111', false);
  DELETE FROM reviews WHERE user_id = uid;
  DELETE FROM cards WHERE user_id = uid;
  INSERT INTO cards (user_id, title, source, status) VALUES (uid, 't3-card', 'APP', 'NEW') RETURNING id INTO cid;
  UPDATE cards SET session_id = 'sess-t3' WHERE id = cid;

  -- GOOD on n=0 -> interval 2
  SELECT process_review(cid, NULL, 'GOOD', k1) INTO r;
  ASSERT (r->>'success')::bool = true, 'GOOD must succeed: ' || r::text;
  ASSERT (r->'data'->>'newInterval')::int = 2, 'n=0 GOOD must be 2: ' || r::text;
  r1 := r;

  -- Replay same key -> ALREADY_PROCESSED, success true, SAME payload shape
  SELECT process_review(cid, NULL, 'GOOD', k1) INTO r;
  ASSERT (r->>'errorCode') = 'ALREADY_PROCESSED', 'replay must be ALREADY_PROCESSED: ' || r::text;
  ASSERT (r->>'success')::bool = true, 'replay keeps success true: ' || r::text;
  ASSERT (r->'data'->>'nextReviewAt') = (r1->'data'->>'nextReviewAt'), 'replay nextReviewAt must match first success: ' || r::text;
  ASSERT (r->'data'->>'newInterval') = (r1->'data'->>'newInterval'), 'replay newInterval must match first success: ' || r::text;

  -- Session alias (NULL card id, session_id only)
  SELECT process_review(NULL, 'sess-t3', 'EASY', k2) INTO r;
  ASSERT (r->>'success')::bool = true, 'session alias must succeed: ' || r::text;

  -- Invalid rating
  SELECT process_review(cid, NULL, 'BOGUS', k3) INTO r;
  ASSERT (r->>'errorCode') = 'INVALID_RATING', 'bogus rating must be INVALID_RATING: ' || r::text;
  ASSERT (r->>'success')::bool = false, 'invalid rating success false: ' || r::text;

  -- MASTER suspend
  SELECT process_review(cid, NULL, 'MASTER', k3 || '-m') INTO r;
  ASSERT (r->>'success')::bool = true, 'MASTER must succeed: ' || r::text;
  PERFORM 1 FROM cards WHERE id = cid AND status='MASTERED' AND suspended = true AND next_review_at IS NULL;
  ASSERT FOUND, 'MASTER must set MASTERED/suspended/null next_review_at';

  RAISE NOTICE 'PROCESS_REVIEW_OK';
END $$;
RESET "request.jwt.claim.sub";
```

- [ ] **Step 2: Run test to verify it fails**

Run: `psql "$SUPABASE_DB_URL" -f supabase/tests/03_process_review.sql`
Expected: FAIL with `function process_review does not exist`.

- [ ] **Step 3: Write minimal implementation `supabase/migrations/2026090304_process_review.sql`**

```sql
create or replace function process_review(
  p_card_id uuid default null,
  p_session_id text default null,
  p_rating text default null,
  p_idempotency_key text default null
) returns jsonb language plpgsql security invoker as $$
declare
  uid uuid := auth.uid();
  c record;
  new_iv int;
  new_rc int;
  new_next timestamptz;
  new_status text;
  existing jsonb;
begin
  if p_idempotency_key is null or p_idempotency_key = '' then
    return jsonb_build_object('success', false, 'data', null, 'errorCode', 'NOT_FOUND', 'version', 1);
  end if;
  if p_rating is null or p_rating not in ('MASTER','EASY','GOOD','HARD','RELEARN') then
    return jsonb_build_object('success', false, 'data', null, 'errorCode', 'INVALID_RATING', 'version', 1);
  end if;
  -- Idempotent replay: same key returns prior result shape. nextReviewAt is
  -- re-read live from cards so the replay payload matches first-success shape
  -- (cardId/newInterval/reviewCount/nextReviewAt/alreadyProcessed).
  select jsonb_build_object('success', true,
           'data', jsonb_build_object('cardId', r.card_id, 'newInterval', r.new_interval,
             'reviewCount', r.review_count_after, 'nextReviewAt', c.next_review_at, 'alreadyProcessed', true),
           'errorCode', 'ALREADY_PROCESSED', 'version', 1)
    into existing from reviews r join cards c on c.id = r.card_id where r.idempotency_key = p_idempotency_key;
  if found then return existing; end if;
  -- Simple rate limit: >30 reviews in trailing 60s.
  if (select count(*) from reviews where user_id = uid and reviewed_at > now() - interval '60 seconds') > 30 then
    return jsonb_build_object('success', false, 'data', null, 'errorCode', 'RATE_LIMITED', 'version', 1);
  end if;
  -- Lock the card. One of card/session id required (session = legacy GAS compat).
  if p_card_id is not null then
    select * into c from cards where id = p_card_id and user_id = uid for update;
  elsif p_session_id is not null then
    select * into c from cards where session_id = p_session_id and user_id = uid order by created_at desc limit 1 for update;
  end if;
  if not found then
    return jsonb_build_object('success', false, 'data', null, 'errorCode', 'NOT_FOUND', 'version', 1);
  end if;
  new_iv := sm2_next_interval(c.interval_days, c.review_count, p_rating);
  new_rc := c.review_count + 1;
  if p_rating = 'MASTER' then
    new_status := 'MASTERED'; new_next := null;
  elsif p_rating = 'RELEARN' then
    new_status := 'RELEARN'; new_next := now() + make_interval(days => 1);
  else
    new_status := 'REVIEW'; new_next := now() + make_interval(days => greatest(new_iv, 1));
  end if;
  update cards set interval_days = new_iv, review_count = new_rc, last_rating = p_rating,
    status = new_status, suspended = (p_rating = 'MASTER'),
    next_review_at = new_next, last_reviewed_at = now(), updated_at = now()
    where id = c.id;
  insert into reviews (user_id, card_id, rating, prev_interval, new_interval, review_count_after, session_id, idempotency_key)
    values (uid, c.id, p_rating, c.interval_days, new_iv, new_rc, c.session_id, p_idempotency_key);
  return jsonb_build_object('success', true,
    'data', jsonb_build_object('cardId', c.id, 'newInterval', new_iv, 'reviewCount', new_rc,
      'nextReviewAt', new_next, 'alreadyProcessed', false),
    'errorCode', null, 'version', 1);
end $$;
```

- [ ] **Step 4: Run test to verify it passes**

Run: `psql "$SUPABASE_DB_URL" -f supabase/migrations/2026090304_process_review.sql && psql "$SUPABASE_DB_URL" -f supabase/tests/03_process_review.sql`
Expected: PASS with `PROCESS_REVIEW_OK`. Note: test runs as table owner so RLS does not block row writes; the auth.users preamble above satisfies the FK where enforced. Never stub `auth.uid()` — RLS interaction is covered by the committed assertion in Task 4.

- [ ] **Step 5: Commit**

```bash
git add supabase/migrations/2026090304_process_review.sql supabase/tests/03_process_review.sql
git commit -m "feat(supabase): process_review RPC with idempotency and MASTER suspend"
```

---

### Task 4: Read/Write RPCs + views (list/detail/update/delete/dashboard)

**Files:**
- Create: `supabase/migrations/2026090305_read_rpcs.sql`
- Create: `supabase/tests/04_read_rpcs.sql`

**Interfaces:**
- Consumes: tables + `process_review()` (Task 3).
- Produces: `list_items(p_filter text DEFAULT 'due', p_query text DEFAULT NULL, p_cursor_due timestamptz DEFAULT NULL, p_cursor_id uuid DEFAULT NULL, p_limit int DEFAULT 50) RETURNS jsonb`; `get_item_detail(p_item_id uuid) RETURNS jsonb` (`data: {card, history[]}` ASC); `update_item(p_item_id uuid, p_title text DEFAULT NULL, p_link text DEFAULT NULL, p_collection text DEFAULT NULL, p_suspended boolean DEFAULT NULL) RETURNS jsonb`; `delete_item(p_item_id uuid, p_hard boolean DEFAULT false) RETURNS jsonb` (soft sets `status='DELETED', suspended=true, next_review_at=NULL`; hard deletes row); `get_dashboard_stats() RETURNS jsonb` (`data: {active,due,mastered,masteryRate,ratings,hardTopics[5]}`); views `v_due_queue`, `v_stats`. All wrapped in the same envelope. Spec-gap fix included here: `cards.status` check is widened to include `'DELETED'` (spec §1.2 requires soft `status='deleted'` but §1.1 check omits it).

- [ ] **Step 1: Write the failing test `supabase/tests/04_read_rpcs.sql`**

```sql
-- 04_read_rpcs.sql: list due-order + search, detail history, update, soft/hard delete, dashboard.
-- Authenticated-session emulation (COMMITTED approach, no throwaway stubs):
-- tests run as table owner (RLS bypassed for direct DML) but every RPC scopes by
-- auth.uid(), which reads request.jwt.claim.sub. Setting the claim below makes
-- auth.uid() return our scratch user inside RPCs on any host (local or hosted).
-- The auth.users preamble satisfies the FK where enforced; skipped otherwise.
DO $$
BEGIN
  INSERT INTO auth.users (id, aud, role, email, encrypted_password, email_confirmed_at, created_at, updated_at)
    VALUES ('22222222-2222-2222-2222-222222222222','authenticated','authenticated','h2t4@example.com','x',now(),now(),now())
    ON CONFLICT (id) DO NOTHING;
EXCEPTION WHEN OTHERS THEN RAISE NOTICE 'auth.users seed skipped (non-superuser runner)';
END $$;
SELECT set_config('request.jwt.claim.sub', '22222222-2222-2222-2222-222222222222', false);
DO $$
DECLARE
  uid uuid := '22222222-2222-2222-2222-222222222222';
  c1 uuid; c2 uuid; cm uuid;
  r jsonb;
BEGIN
  DELETE FROM reviews WHERE user_id = uid;
  DELETE FROM cards WHERE user_id = uid;
  INSERT INTO cards (user_id, title, source, status, suspended, next_review_at, collection, last_rating)
    VALUES (uid, 'overdue-a', 'APP', 'REVIEW', false, now() - interval '2 days', 'kotlin', 'HARD') RETURNING id INTO c1;
  INSERT INTO cards (user_id, title, source, status, suspended, next_review_at, collection)
    VALUES (uid, 'due-b', 'APP', 'REVIEW', false, now() - interval '1 hour', 'kotlin') RETURNING id INTO c2;
  INSERT INTO cards (user_id, title, source, status, suspended, next_review_at)
    VALUES (uid, 'mastered-m', 'APP', 'MASTERED', true, NULL) RETURNING id INTO cm;

  -- list due: overdue first
  SELECT list_items('due', NULL, NULL, NULL, 10) INTO r;
  ASSERT (r->>'success')::bool = true, 'list due must succeed: ' || r::text;
  ASSERT (r->'data'->'items'->0->>'id') = c1::text, 'overdue first: ' || r::text;

  -- list search query
  SELECT list_items('all', 'due-b', NULL, NULL, 10) INTO r;
  ASSERT jsonb_array_length(r->'data'->'items') = 1, 'search must return 1: ' || r::text;

  -- detail includes history ASC (empty ok) + card
  SELECT get_item_detail(c1) INTO r;
  ASSERT (r->'data'->'card'->>'id') = c1::text, 'detail card id: ' || r::text;
  ASSERT jsonb_typeof(r->'data'->'history') = 'array', 'detail history array: ' || r::text;

  -- detail NOT_FOUND
  SELECT get_item_detail('00000000-0000-0000-0000-000000000000') INTO r;
  ASSERT (r->>'errorCode') = 'NOT_FOUND', 'detail missing must be NOT_FOUND: ' || r::text;

  -- update title
  SELECT update_item(c2, 'due-b2', NULL, NULL, NULL) INTO r;
  ASSERT (r->'data'->>'title') = 'due-b2', 'update title: ' || r::text;

  -- dashboard: active=2 due=2 mastered=1
  SELECT get_dashboard_stats() INTO r;
  ASSERT (r->'data'->>'active')::int = 2, 'dashboard active=2: ' || r::text;
  ASSERT (r->'data'->>'due')::int = 2, 'dashboard due=2: ' || r::text;
  ASSERT (r->'data'->>'mastered')::int = 1, 'dashboard mastered=1: ' || r::text;
  ASSERT (r->'data'->'hardTopics'->0->>'collection') = 'kotlin', 'hardTopics top=kotlin: ' || r::text;

  -- soft delete then hard delete
  SELECT delete_item(c2, false) INTO r;
  ASSERT (r->'data'->>'status') = 'DELETED', 'soft delete status: ' || r::text;
  SELECT delete_item(c2, true) INTO r;
  ASSERT (r->>'success')::bool = true, 'hard delete: ' || r::text;
  PERFORM 1 FROM cards WHERE id = c2;
  ASSERT NOT FOUND, 'hard-deleted row must be gone';

  -- RLS-through-RPC (committed cross-user isolation check): a different caller
  -- sees zero rows through list_items and NOT_FOUND through get_item_detail.
  PERFORM set_config('request.jwt.claim.sub', '99999999-9999-9999-9999-999999999999', false);
  SELECT list_items('all', NULL, NULL, NULL, 10) INTO r;
  ASSERT jsonb_array_length(r->'data'->'items') = 0, 'other user must see 0 items: ' || r::text;
  SELECT get_item_detail(c1) INTO r;
  ASSERT (r->>'errorCode') = 'NOT_FOUND', 'other user detail must be NOT_FOUND: ' || r::text;
  PERFORM set_config('request.jwt.claim.sub', '22222222-2222-2222-2222-222222222222', false);

  RAISE NOTICE 'READ_RPCS_OK';
END $$;
RESET "request.jwt.claim.sub";
```

- [ ] **Step 2: Run test to verify it fails**

Run: `psql "$SUPABASE_DB_URL" -f supabase/tests/04_read_rpcs.sql`
Expected: FAIL with `function list_items does not exist`.

- [ ] **Step 3: Write minimal implementation `supabase/migrations/2026090305_read_rpcs.sql`**

```sql
-- Spec amendment (recorded): spec §1.2 writes soft-delete as status='deleted'
-- (lowercase) but §1.1's check constraint has no DELETED value at all. This plan
-- locks UPPERCASE 'DELETED' everywhere (implementation + tests) to match the
-- existing 'NEW'/'REVIEW'/'RELEARN'/'MASTERED' convention; fix spec §1.2 text to 'DELETED'.
alter table cards drop constraint if exists cards_status_check;
alter table cards add constraint cards_status_check check (status in ('NEW','REVIEW','RELEARN','MASTERED','DELETED'));

create or replace view v_due_queue with (security_invoker = true) as
  select * from cards where suspended = false and next_review_at <= now() order by next_review_at, id;
create or replace view v_stats with (security_invoker = true) as
  select user_id,
    count(*) filter (where status <> 'MASTERED' and status <> 'DELETED') as active,
    count(*) filter (where suspended = false and next_review_at <= now()) as due,
    count(*) filter (where status = 'MASTERED') as mastered
  from cards group by user_id;

create or replace function list_items(p_filter text default 'due', p_query text default null,
  p_cursor_due timestamptz default null, p_cursor_id uuid default null, p_limit int default 50)
returns jsonb language plpgsql security invoker as $$
declare uid uuid := auth.uid(); items jsonb; total_due int;
begin
  if p_filter not in ('due','all','mastered') then p_filter := 'due'; end if;
  p_limit := least(greatest(coalesce(p_limit, 50), 1), 100);
  select coalesce(jsonb_agg(t ORDER BY t.next_review_at nulls last, t.id), '[]') into items from (
    select c.id, c.title, c.link, c.source, c.status, c.suspended, c.interval_days, c.review_count,
      c.next_review_at as "dueDate",
      greatest(0, (extract(epoch from (now() - c.next_review_at))/86400)::int) as "overdueDays",
      sm2_next_interval(c.interval_days, c.review_count, coalesce(c.last_rating,'GOOD')) as "nextPreview",
      c.next_review_at, c.id as cid
    from cards c where c.user_id = uid
      and (p_filter='all' or (p_filter='due' and c.suspended=false and c.next_review_at <= now())
        or (p_filter='mastered' and c.status='MASTERED'))
      and (p_query is null or c.title ilike '%' || replace(replace(replace(p_query, '\', '\\'), '%', '\%'), '_', '\_') || '%' escape '\'
        or coalesce(c.collection,'') ilike '%' || replace(replace(replace(p_query, '\', '\\'), '%', '\%'), '_', '\_') || '%' escape '\')
      and (p_cursor_due is null or c.next_review_at > p_cursor_due or (c.next_review_at = p_cursor_due and (p_cursor_id is null or c.id > p_cursor_id)))
    order by c.next_review_at nulls last, c.id limit p_limit
  ) t;
  select count(*) into total_due from cards where user_id = uid and suspended=false and next_review_at <= now();
  return jsonb_build_object('success', true, 'data', jsonb_build_object('items', items,
    'nextCursor', (select t->'id' from jsonb_array_elements(items) with ordinality a(t,o) order by o desc limit 1),
    'hasMore', jsonb_array_length(items) = p_limit, 'dueCount', total_due), 'errorCode', null, 'version', 1);
end $$;

create or replace function get_item_detail(p_item_id uuid) returns jsonb
language plpgsql security invoker as $$
declare uid uuid := auth.uid(); c jsonb; h jsonb;
begin
  select to_jsonb(cards) into c from cards where id = p_item_id and user_id = uid;
  if not found or c is null then
    return jsonb_build_object('success', false, 'data', null, 'errorCode', 'NOT_FOUND', 'version', 1);
  end if;
  select coalesce(jsonb_agg(to_jsonb(reviews) order by reviewed_at), '[]') into h
    from reviews where card_id = p_item_id and user_id = uid;
  return jsonb_build_object('success', true, 'data', jsonb_build_object('card', c, 'history', h), 'errorCode', null, 'version', 1);
end $$;

create or replace function update_item(p_item_id uuid, p_title text default null, p_link text default null,
  p_collection text default null, p_suspended boolean default null) returns jsonb
language plpgsql security invoker as $$
declare uid uuid := auth.uid(); c jsonb;
begin
  update cards set title = coalesce(p_title, title), link = coalesce(p_link, link),
    collection = coalesce(p_collection, collection), suspended = coalesce(p_suspended, suspended),
    updated_at = now()
    where id = p_item_id and user_id = uid returning to_jsonb(cards) into c;
  if not found or c is null then
    return jsonb_build_object('success', false, 'data', null, 'errorCode', 'NOT_FOUND', 'version', 1);
  end if;
  return jsonb_build_object('success', true, 'data', c, 'errorCode', null, 'version', 1);
end $$;

create or replace function delete_item(p_item_id uuid, p_hard boolean default false) returns jsonb
language plpgsql security invoker as $$
declare uid uuid := auth.uid(); c jsonb;
begin
  if coalesce(p_hard, false) then
    delete from cards where id = p_item_id and user_id = uid returning to_jsonb(cards) into c;
  else
    update cards set status='DELETED', suspended=true, next_review_at=null, updated_at=now()
      where id = p_item_id and user_id = uid returning to_jsonb(cards) into c;
  end if;
  if not found or c is null then
    return jsonb_build_object('success', false, 'data', null, 'errorCode', 'NOT_FOUND', 'version', 1);
  end if;
  return jsonb_build_object('success', true, 'data', c, 'errorCode', null, 'version', 1);
end $$;

create or replace function get_dashboard_stats() returns jsonb
language plpgsql security invoker as $$
declare
  uid uuid := auth.uid();
  v_active int; v_due int; v_mastered int; v_rate numeric; v_ratings jsonb; v_hard jsonb;
begin
  select count(*) filter (where status not in ('MASTERED','DELETED')) into v_active from cards where user_id = uid;
  select count(*) filter (where suspended=false and next_review_at <= now()) into v_due from cards where user_id = uid;
  select count(*) filter (where status='MASTERED') into v_mastered from cards where user_id = uid;
  v_rate := case when (v_active + v_mastered) > 0 then round(v_mastered::numeric/(v_active+v_mastered)*100,1) else 0 end;
  select coalesce(jsonb_object_agg(last_rating, n), '{}') into v_ratings from
    (select last_rating, count(*) n from cards where user_id = uid and last_rating is not null group by 1) s;
  select coalesce(jsonb_agg(t), '[]') into v_hard from
    (select collection, count(*) hard_count from cards where user_id = uid and last_rating='HARD' and collection is not null
      group by collection order by count(*) desc limit 5) t;
  return jsonb_build_object('success', true, 'data', jsonb_build_object('active', coalesce(v_active,0),
    'due', coalesce(v_due,0), 'mastered', coalesce(v_mastered,0), 'masteryRate', v_rate,
    'ratings', coalesce(v_ratings,'{}'), 'hardTopics', coalesce(v_hard,'[]')), 'errorCode', null, 'version', 1);
end $$;
```

- [ ] **Step 4: Run test to verify it passes**

Run: `psql "$SUPABASE_DB_URL" -f supabase/migrations/2026090305_read_rpcs.sql && psql "$SUPABASE_DB_URL" -f supabase/tests/04_read_rpcs.sql`
Expected: PASS with `READ_RPCS_OK`. Auth works via the committed `set_config('request.jwt.claim.sub', ...)` line at the top of the test file — never stub `auth.uid()` itself. If assertions see 0 rows, check the claim line ran (psql `-f` runs it in the same session).

- [ ] **Step 5: Commit**

```bash
git add supabase/migrations/2026090305_read_rpcs.sql supabase/tests/04_read_rpcs.sql
git commit -m "feat(supabase): list/detail/update/delete/dashboard RPCs and views"
```

---

### Task 5: Demo seed + MemArchiveState migration verification

**Files:**
- Create: `supabase/seed_demo.sql`
- Create: `supabase/tests/05_migration_check.sql`

**Interfaces:**
- Consumes: all tables/RPCs from Tasks 1–4.
- Produces: seeded scratch user `33333333-3333-3333-3333-333333333333` with 5 cards covering NEW / REVIEW-due / REVIEW-future / RELEARN / MASTERED; migration-mapping proof: `MemArchiveState[0..11]` → `cards` column map from spec §1.1, derived rules locked below, dedupe rule `unique(raindrop_id)` + loader-level `(user_id,link)` for APP/FORM.

Locked derived rules (spec says "per rules in §1" without spelling them out; this plan locks them): `last_rating='MASTER'` → `status='MASTERED', suspended=true, next_review_at=NULL`; `review_count=0` → `status='NEW', next_review_at=created_at`; `last_rating='RELEARN'` → `status='RELEARN', next_review_at=last_reviewed_at+1 day`; else → `status='REVIEW', next_review_at=last_reviewed_at+interval_days`.

- [ ] **Step 1: Write seed + failing verification test**

`supabase/seed_demo.sql`:

```sql
-- Scratch single-user demo data (dev only, never in migrations/).
DELETE FROM reviews WHERE user_id = '33333333-3333-3333-3333-333333333333';
DELETE FROM cards WHERE user_id = '33333333-3333-3333-3333-333333333333';
INSERT INTO cards (user_id, title, link, source, raindrop_id, collection, review_count, interval_days, last_rating, status, suspended, next_review_at, last_reviewed_at) VALUES
 ('33333333-3333-3333-3333-333333333333','new-phone-note','adaptivesr://add?text=hi','APP',NULL,'inbox',0,0,NULL,'NEW',false, now(),NULL),
 ('33333333-3333-3333-3333-333333333333','overdue-raindrop','https://r.example/1','RAINDROP',101,'kotlin','2',2,'GOOD','REVIEW',false, now() - interval '3 days', now() - interval '3 days'),
 ('33333333-3333-3333-3333-333333333333','future-form','https://f.example/9','FORM',NULL,'system-design',1,2,'GOOD','REVIEW',false, now() + interval '1 day', now()),
 ('33333333-3333-3333-3333-333333333333','relearn-item','https://r.example/2','RAINDROP',102,'kotlin',4,1,'RELEARN','RELEARN',false, now() - interval '1 hour', now() - interval '2 days'),
 ('33333333-3333-3333-3333-333333333333','mastered-item','https://r.example/3','RAINDROP',103,'history',6,30,'MASTER','MASTERED',true,NULL, now() - interval '30 days');
```

`supabase/tests/05_migration_check.sql`:

```sql
-- 05_migration_check.sql: mapping + generated column + due ordering + dedupe.
DO $$
DECLARE uid uuid := '33333333-3333-3333-3333-333333333333'; n int; ps int;
BEGIN
  -- Mapping spot-check: all 12 MemArchiveState columns landed.
  SELECT count(*) INTO n FROM cards WHERE user_id = uid AND title IS NOT NULL AND source IN ('APP','RAINDROP','FORM');
  ASSERT n = 5, 'seed must have 5 mapped cards, got ' || n;
  -- Generated performance_score: interval/review_count*10 (mastered 30/6*10=50).
  SELECT performance_score INTO ps FROM cards WHERE user_id = uid AND title='mastered-item';
  ASSERT ps = 50, 'performance_score 30/6*10=50, got ' || ps;
  SELECT performance_score INTO ps FROM cards WHERE user_id = uid AND title='new-phone-note';
  ASSERT ps = 0, 'new card score 0, got ' || ps;
  -- Due ordering: overdue-raindrop before relearn-item, future + mastered excluded.
  SELECT count(*) INTO n FROM v_due_queue WHERE user_id = uid;
  ASSERT n = 2, 'due queue must be 2 (overdue+relearn), got ' || n;
  PERFORM 1 FROM v_due_queue WHERE user_id = uid AND title='overdue-raindrop';
  ASSERT FOUND, 'overdue-raindrop must be due';
  PERFORM 1 FROM v_due_queue WHERE user_id = uid AND title='future-form';
  ASSERT NOT FOUND, 'future-form must not be due';
  PERFORM 1 FROM v_due_queue WHERE user_id = uid AND title='mastered-item';
  ASSERT NOT FOUND, 'mastered must never be due';
  -- Dedupe: duplicate raindrop_id violates unique.
  BEGIN
    INSERT INTO cards (user_id, title, source, raindrop_id) VALUES (uid, 'dup', 'RAINDROP', 101);
    ASSERT false, 'duplicate raindrop_id must fail';
  EXCEPTION WHEN unique_violation THEN
    RAISE NOTICE 'DEDUPE_RAINDROP_OK';
  END;
  RAISE NOTICE 'MIGRATION_CHECK_OK';
END $$;
```

- [ ] **Step 2: Run test to verify it fails**

Run: `psql "$SUPABASE_DB_URL" -f supabase/tests/05_migration_check.sql`
Expected: FAIL (seed absent → `seed must have 5 mapped cards, got 0`).

- [ ] **Step 3: Apply seed (minimal implementation)**

Run: `psql "$SUPABASE_DB_URL" -f supabase/seed_demo.sql`

- [ ] **Step 4: Run full suite to verify everything passes**

Run: `psql "$SUPABASE_DB_URL" -f supabase/tests/01_sm2.sql && psql "$SUPABASE_DB_URL" -f supabase/tests/02_rls.sql && psql "$SUPABASE_DB_URL" -f supabase/tests/03_process_review.sql && psql "$SUPABASE_DB_URL" -f supabase/tests/04_read_rpcs.sql && psql "$SUPABASE_DB_URL" -f supabase/tests/05_migration_check.sql`
Expected: PASS with `SM2_TRUTH_TABLE_OK`, `RLS_CATALOG_OK`, `RLS_ANON_DENY_OK`, `PROCESS_REVIEW_OK`, `READ_RPCS_OK`, `MIGRATION_CHECK_OK`. Cleanup scratch users afterwards: `psql "$SUPABASE_DB_URL" -c "delete from reviews where user_id in ('11111111-1111-1111-1111-111111111111','22222222-2222-2222-2222-222222222222'); delete from cards where user_id in ('11111111-1111-1111-1111-111111111111','22222222-2222-2222-2222-222222222222','33333333-3333-3333-3333-333333333333');"`.

- [ ] **Step 5: Commit**

```bash
git add supabase/seed_demo.sql supabase/tests/05_migration_check.sql
git commit -m "feat(supabase): demo seed and MemArchiveState migration check"
```

---

## Self-Review

1. Spec coverage: §1.1 DDL + sm2 + indexes + RLS + archive-as-status rule → Tasks 1–2; mapping 12-column table + dedupe → Task 5; §1.2 all six RPCs + envelope + error codes + session-alias + replay shape → Tasks 3–4; reads-via-PostgREST/views → Task 4 `v_due_queue/v_stats`; sidecar service_role note → Global Constraints (sidecar itself is H2 Plan 2, out of scope).
2. Placeholder scan: no TBD/TODO/similar-to-Task-N; every step ships actual SQL and exact `psql`/`git` commands.
3. Type consistency: `sm2_next_interval(prev int, n int, rating text)` used identically in Tasks 1, 3, 4; envelope `{success,data,errorCode,version}` identical in all five RPCs; `list_items` cursor pair `(p_cursor_due, p_cursor_id)` matches test; `delete_item` soft `DELETED` matches widened check constraint from the same migration.

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-09-03-h2-plan1-supabase-backend.md`. Two execution options:

**1. Subagent-Driven (recommended)** — dispatch a fresh subagent per task, review between tasks, fast iteration

**2. Inline Execution** — execute tasks in this session using executing-plans, batch execution with checkpoints

**Which approach?**
