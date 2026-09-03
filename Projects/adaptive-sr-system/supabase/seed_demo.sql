-- Scratch single-user demo data (dev only, never in migrations/).
DO $$
BEGIN
  INSERT INTO auth.users (id, aud, role, email, encrypted_password, email_confirmed_at, created_at, updated_at)
    VALUES ('33333333-3333-3333-3333-333333333333','authenticated','authenticated','h2seed@example.com','x',now(),now(),now())
    ON CONFLICT (id) DO NOTHING;
EXCEPTION WHEN OTHERS THEN RAISE NOTICE 'auth.users seed skipped (non-superuser runner)';
END $$;
DELETE FROM reviews WHERE user_id = '33333333-3333-3333-3333-333333333333';
DELETE FROM cards WHERE user_id = '33333333-3333-3333-3333-333333333333';
INSERT INTO cards (user_id, title, link, source, raindrop_id, collection, review_count, interval_days, last_rating, status, suspended, next_review_at, last_reviewed_at) VALUES
 ('33333333-3333-3333-3333-333333333333','new-phone-note','adaptivesr://add?text=hi','APP',NULL,'inbox',0,0,NULL,'NEW',false, now(),NULL),
 ('33333333-3333-3333-3333-333333333333','overdue-raindrop','https://r.example/1','RAINDROP',101,'kotlin','2',2,'GOOD','REVIEW',false, now() - interval '3 days', now() - interval '3 days'),
 ('33333333-3333-3333-3333-333333333333','future-form','https://f.example/9','FORM',NULL,'system-design',1,2,'GOOD','REVIEW',false, now() + interval '1 day', now()),
 ('33333333-3333-3333-3333-333333333333','relearn-item','https://r.example/2','RAINDROP',102,'kotlin',4,1,'RELEARN','RELEARN',false, now() - interval '1 hour', now() - interval '2 days'),
 ('33333333-3333-3333-3333-333333333333','mastered-item','https://r.example/3','RAINDROP',103,'history',6,30,'MASTER','MASTERED',true,NULL, now() - interval '30 days');
