-- CORE-06: profile writes are self-only and avatars get a Storage policy.

-- Same check profile_household used inline. Security invoker: RLS on member shows an active member
-- every row of their own household, and any blind spot fails closed (no shared household).
create or replace function shares_household(uid uuid) returns boolean
language sql stable as $$
  select exists (
    select 1 from member a join member b on a.household_id = b.household_id
    where a.user_id = auth.uid() and b.user_id = uid
      and a.left_at is null and b.left_at is null
  );
$$;

-- Clients never insert or delete a profile (SPEC §11): rows come from handle_new_user and cascade from auth.users.
drop policy profile_self on profile;
drop policy profile_household on profile;
create policy profile_select on profile for select
  using (id = auth.uid() or shares_household(id));
-- USING widens to peers so a peer's update reaches the check and raises 42501; strangers match 0 rows.
create policy profile_update on profile for update
  using (id = auth.uid() or shares_household(id)) with check (id = auth.uid());

-- Timestamps are server-owned: a queued offline edit must not forge them.
create or replace function profile_touch() returns trigger
language plpgsql as $$
begin
  new.updated_at := now();
  new.created_at := old.created_at;
  return new;
end $$;
create trigger profile_touch before update on profile
  for each row execute function profile_touch();

-- Storage: avatars at profiles/{user_id}/{filename} in the private households bucket.
insert into storage.buckets (id, name, public) values ('households', 'households', false)
  on conflict (id) do nothing;

-- Folder 2 is compared as text; the uuid cast runs only behind the regex (CASE guarantees order).
create policy avatar_select on storage.objects for select using (
  bucket_id = 'households' and (storage.foldername(name))[1] = 'profiles'
  and case
    when (storage.foldername(name))[2] = auth.uid()::text then true
    when (storage.foldername(name))[2] ~ '^[0-9a-f-]{36}$'
      then shares_household(((storage.foldername(name))[2])::uuid)
    else false
  end);
create policy avatar_insert on storage.objects for insert with check (
  bucket_id = 'households' and (storage.foldername(name))[1] = 'profiles'
  and (storage.foldername(name))[2] = auth.uid()::text and array_length(storage.foldername(name), 1) = 2);
create policy avatar_update on storage.objects for update
  using (bucket_id = 'households' and (storage.foldername(name))[1] = 'profiles'
    and (storage.foldername(name))[2] = auth.uid()::text and array_length(storage.foldername(name), 1) = 2)
  with check (bucket_id = 'households' and (storage.foldername(name))[1] = 'profiles'
    and (storage.foldername(name))[2] = auth.uid()::text and array_length(storage.foldername(name), 1) = 2);
create policy avatar_delete on storage.objects for delete using (
  bucket_id = 'households' and (storage.foldername(name))[1] = 'profiles'
  and (storage.foldername(name))[2] = auth.uid()::text and array_length(storage.foldername(name), 1) = 2);
