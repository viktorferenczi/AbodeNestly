-- CORE-01: create a profile row the first time a user signs in (any provider).
-- Copied verbatim from the "Core -> profile" block of docs/schema.sql.

create table profile (
  id            uuid primary key references auth.users(id) on delete cascade,
  display_name  text not null,
  avatar_path   text,
  created_at    timestamptz not null default now(),
  updated_at    timestamptz not null default now()
);
alter table profile enable row level security;
create policy profile_self on profile for all using (id = auth.uid());
-- members of the same household can see each other's name/avatar
create policy profile_household on profile for select using (
  exists (select 1 from member a join member b on a.household_id = b.household_id
          where a.user_id = auth.uid() and b.user_id = profile.id)
);

-- Create a profile row the first time a user signs in (any provider).
create or replace function handle_new_user() returns trigger
language plpgsql security definer set search_path = public as $$
begin
  insert into profile(id, display_name, avatar_path)
  values (new.id,
          coalesce(new.raw_user_meta_data->>'full_name',
                   new.raw_user_meta_data->>'name',
                   split_part(new.email, '@', 1)),
          new.raw_user_meta_data->>'avatar_url')
  on conflict (id) do nothing;
  return new;
end $$;
create trigger on_auth_user_created after insert on auth.users
  for each row execute function handle_new_user();
