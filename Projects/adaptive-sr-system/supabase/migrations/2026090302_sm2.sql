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
