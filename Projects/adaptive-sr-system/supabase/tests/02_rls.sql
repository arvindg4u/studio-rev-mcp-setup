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
