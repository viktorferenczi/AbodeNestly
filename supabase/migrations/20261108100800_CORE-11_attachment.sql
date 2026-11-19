-- CORE-11: attachment rows (photos/PDFs of pantry items, assets, log entries) and household Storage policies.

create table attachment (
  id            uuid primary key default gen_random_uuid(),
  household_id  uuid not null references household(id) on delete cascade,
  entity        text not null,
  entity_id     uuid not null,
  storage_path  text not null,          -- households/{hid}/{entity}/{id}/{file}
  mime          text not null,
  size_bytes    bigint not null check (size_bytes >= 0),
  created_by    uuid references auth.users(id),
  created_at    timestamptz not null default now(),
  updated_at    timestamptz not null default now(),
  deleted_at    timestamptz,
  sync_seq      bigint not null default nextval('sync_seq_global'),
  -- A row can never point at another household's object (nor at a different entity's folder).
  check (
    starts_with(storage_path, 'households/' || household_id::text || '/' || entity || '/' || entity_id::text || '/')
    -- exactly one non-empty file segment after the folder; LIKE would treat _ and % in entity as wildcards
    and substr(storage_path, length('households/' || household_id::text || '/' || entity || '/' || entity_id::text || '/') + 1) ~ '^[^/]+$')
);
call setup_scoped('attachment');
-- Also serves "attachments of this entity"; no further index needed.
create index attachment_entity_idx on attachment(entity, entity_id);

-- A member cannot attribute an upload to someone else (same rule as activity, CORE-08).
drop policy attachment_insert on attachment;
create policy attachment_insert on attachment for insert with check (
  is_member(household_id) and created_by = auth.uid());

-- A WITH CHECK cannot see OLD, so a trigger keeps the uploader fixed for clients (same reason as guard_activity_update).
-- Only created_by is frozen: deleted_at must stay writable for soft delete. Service-role writes are not restricted.
create or replace function guard_attachment_update() returns trigger
language plpgsql as $$
begin
  if current_user = 'authenticated' and new.created_by is distinct from old.created_by then
    raise exception 'attachment_created_by_immutable' using errcode = '42501';
  end if;
  return new;
end $$;
create trigger attachment_guard before update on attachment for each row execute function guard_attachment_update();

alter publication supabase_realtime add table attachment;

-- Storage: households/{household_id}/{entity}/{entity_id}/{file} in the private households bucket.
-- Folder 2 is cast to uuid only behind the regex (CASE guarantees order), so malformed keys are hidden, not raising.
create policy household_files_select on storage.objects for select using (
  bucket_id = 'households' and (storage.foldername(name))[1] = 'households'
  and case
    when (storage.foldername(name))[2] ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
      then is_member(((storage.foldername(name))[2])::uuid)
    else false
  end);
create policy household_files_insert on storage.objects for insert with check (
  bucket_id = 'households' and (storage.foldername(name))[1] = 'households'
  and array_length(storage.foldername(name), 1) = 4
  and case
    when (storage.foldername(name))[2] ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
      then is_member(((storage.foldername(name))[2])::uuid)
    else false
  end);
create policy household_files_update on storage.objects for update
  using (
    bucket_id = 'households' and (storage.foldername(name))[1] = 'households'
    and array_length(storage.foldername(name), 1) = 4
    and case
      when (storage.foldername(name))[2] ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
        then is_member(((storage.foldername(name))[2])::uuid)
      else false
    end)
  with check (
    bucket_id = 'households' and (storage.foldername(name))[1] = 'households'
    and array_length(storage.foldername(name), 1) = 4
    and case
      when (storage.foldername(name))[2] ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
        then is_member(((storage.foldername(name))[2])::uuid)
      else false
    end);
create policy household_files_delete on storage.objects for delete using (
  bucket_id = 'households' and (storage.foldername(name))[1] = 'households'
  and case
    when (storage.foldername(name))[2] ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
      then is_member(((storage.foldername(name))[2])::uuid)
    else false
  end);
