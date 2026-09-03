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
  -- Due ordering: overdue-raindrop + relearn-item + new-phone-note (NEW cards are
  -- due immediately per locked derived rule next_review_at=created_at); future + mastered excluded.
  SELECT count(*) INTO n FROM v_due_queue WHERE user_id = uid;
  ASSERT n = 3, 'due queue must be 3 (overdue+relearn+new), got ' || n;
  PERFORM 1 FROM v_due_queue WHERE user_id = uid AND title='new-phone-note';
  ASSERT FOUND, 'new-phone-note (NEW) must be due immediately';
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
