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
