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
