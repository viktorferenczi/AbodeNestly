-- CORE-03: invites expire, run out of uses and join the caller with the invite's role.

create table invite (
  id            uuid primary key default gen_random_uuid(),
  household_id  uuid not null references household(id) on delete cascade,
  code          text not null unique default encode(gen_random_bytes(6), 'hex'),
  role          text not null default 'member' check (role in ('owner','member')),
  created_by    uuid not null references auth.users(id),
  expires_at    timestamptz not null default now() + interval '7 days',
  max_uses      int not null default 10,
  uses          int not null default 0,
  created_at    timestamptz not null default now(),
  updated_at    timestamptz not null default now(),
  deleted_at    timestamptz,
  sync_seq      bigint not null default nextval('sync_seq_global')
);
call setup_scoped('invite');
-- Only owners may mint owner invites; members may create member invites.
drop policy invite_insert on invite;
create policy invite_insert on invite for insert with check (
  is_member(household_id) and created_by = auth.uid()
  and (role = 'member' or is_owner(household_id)));

alter publication supabase_realtime add table invite;

create or replace function accept_invite(p_code text) returns uuid
language plpgsql security definer set search_path = public as $$
declare inv invite;
begin
  if auth.uid() is null then
    raise exception 'not_authenticated' using errcode = '42501';
  end if;
  select * into inv from invite
   where code = p_code and deleted_at is null and expires_at > now() and uses < max_uses
   for update;
  if not found then raise exception 'invalid_invite'; end if;
  -- Already an active member: no-op (no demotion, no use burned).
  if exists (select 1 from member
              where household_id = inv.household_id and user_id = auth.uid() and left_at is null) then
    return inv.household_id;
  end if;
  insert into member(household_id, user_id, role) values (inv.household_id, auth.uid(), inv.role)
    on conflict (household_id, user_id) do update set left_at = null, role = excluded.role;
  update invite set uses = uses + 1 where id = inv.id;
  return inv.household_id;
end $$;
