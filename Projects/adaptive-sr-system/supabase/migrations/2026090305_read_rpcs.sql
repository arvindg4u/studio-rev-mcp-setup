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
