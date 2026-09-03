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
             'reviewCount', r.review_count_after, 'nextReviewAt', cc.next_review_at, 'alreadyProcessed', true),
           'errorCode', 'ALREADY_PROCESSED', 'version', 1)
    into existing from reviews r join cards cc on cc.id = r.card_id where r.idempotency_key = p_idempotency_key;
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
