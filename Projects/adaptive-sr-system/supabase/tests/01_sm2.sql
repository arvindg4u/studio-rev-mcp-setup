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
