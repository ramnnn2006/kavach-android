-- Kavach round 2. Run as database owner in Supabase SQL editor after DASHBOARD-STEPS.md.
-- Based on ramnnn2006/kavach migrations plus live read-only schema verification.
-- Does not contain credentials. All changes roll back together on preflight failure.
begin;
set local lock_timeout = '5s';
-- Refuse dirty historical data instead of silently deleting or truncating residents' records.
do $$ begin
  if exists(select 1 from public.flats group by zone_id, upper(number) having count(*) > 1) then
    raise exception 'Duplicate flats remain. Follow DASHBOARD-STEPS before applying.';
  end if;
  if exists(select 1 from public.incidents where source='safety_check' and public.is_active_status(status) group by safety_check_id,reporter_id having count(*)>1) then raise exception 'Duplicate open safety-check incidents remain. Review before applying.'; end if;
  if exists(select 1 from public.safety_checks where ended_at is null group by society_id having count(*) > 1) then
    raise exception 'Multiple active safety checks remain. Review and end old checks first.';
  end if;
end $$;
create unique index if not exists flats_zone_number_r2 on public.flats(zone_id, upper(number));
create unique index if not exists safety_checks_one_active_r2 on public.safety_checks(society_id) where ended_at is null;
create unique index if not exists incidents_one_check_help_r2 on public.incidents(safety_check_id, reporter_id)
  where source = 'safety_check' and status in ('pending','acknowledged','en_route','on_scene');
-- NOT VALID preserves old data for owner review while enforcing all new/changed rows.
alter table public.incidents add constraint incidents_location_note_r2 check (length(location_note) <= 500) not valid;
alter table public.incidents add constraint incidents_resolution_note_r2 check (length(resolution_note) <= 1000) not valid;
alter table public.incidents add constraint incidents_people_r2 check (people_affected between 1 and 500) not valid;
alter table public.incident_events add constraint incident_events_note_r2 check (length(note) <= 1000) not valid;


create or replace function public.r2_approved_signup_guard() returns trigger
language plpgsql security definer set search_path = public as $$
begin
  if nullif(new.phone, '') is null or not exists (
    select 1 from public.members m where regexp_replace(m.phone, '[^0-9]', '', 'g') = regexp_replace(new.phone, '[^0-9]', '', 'g')
  ) then raise exception 'forbidden' using detail = 'This number is not on the approved member list'; end if;
  return new;
end $$;
drop trigger if exists r2_approved_signup_guard on auth.users;
create trigger r2_approved_signup_guard before insert on auth.users for each row execute function public.r2_approved_signup_guard();
revoke all on function public.r2_approved_signup_guard() from public, anon, authenticated;


create or replace function public.claim_incident(p_incident_id uuid)
returns public.incidents
language plpgsql security definer
set search_path = public
as $$
declare
  me  public.profiles%rowtype;
  inc public.incidents%rowtype;
begin
  select * into me from public.profiles where id = auth.uid() for update;
  if me.id is null or me.role not in ('responder', 'admin') then
    raise exception 'forbidden' using detail = 'Only responders can claim alerts';
  end if;

  select * into inc from public.incidents
   where id = p_incident_id and society_id = me.society_id
   for update;
  if not found then
    raise exception 'not_found';
  end if;
  if me.role = 'responder' and not (inc.type = any (me.specialties)) then
    raise exception 'forbidden';
  end if;
  if not public.is_active_status(inc.status) then
    raise exception 'invalid' using detail = 'Incident is already closed';
  end if;
  if inc.assigned_to = me.id then return inc; end if;
  if inc.status <> 'pending' or inc.assigned_to is not null then
    raise exception 'already_claimed';
  end if;

  update public.incidents
     set status = 'acknowledged',
         assigned_to = me.id, assigned_name = me.full_name, assigned_phone = me.phone,
         assigned_at = now(), acknowledged_at = now()
   where id = inc.id
   returning * into inc;
  return inc;
end;
$$;

create or replace function public.advance_incident(p_incident_id uuid, p_next public.incident_status, p_note text default null)
returns public.incidents
language plpgsql security definer
set search_path = public
as $$
declare
  me   public.profiles%rowtype;
  inc  public.incidents%rowtype;
  note text := nullif(trim(coalesce(p_note, '')), '');
begin
  select * into me from public.profiles where id = auth.uid();
  if me.id is null or me.role not in ('responder', 'admin') then
    raise exception 'forbidden';
  end if;

  select * into inc from public.incidents
   where id = p_incident_id and society_id = me.society_id
   for update;
  if not found then
    raise exception 'not_found';
  end if;
  if me.role = 'responder' and inc.assigned_to is distinct from me.id then
    raise exception 'forbidden' using detail = 'Only the assigned responder can update this alert';
  end if;
  if not public.is_active_status(inc.status) then
    raise exception 'invalid' using detail = 'Incident is already closed';
  end if;
  if p_next in ('pending', 'cancelled') then
    raise exception 'invalid' using detail = 'Use cancel_incident to cancel';
  end if;
  if public.status_rank(p_next) <= public.status_rank(inc.status) then
    raise exception 'invalid' using detail = 'Status can only move forward';
  end if;

  if length(note) > 1000 then raise exception 'invalid' using detail = 'Note must be at most 1000 characters'; end if;
  if p_next is null or public.status_rank(p_next) <> public.status_rank(inc.status) + 1 then
    raise exception 'invalid' using detail = 'Move to the next status first';
  end if;
  update public.incidents
     set status = p_next,
         assigned_to    = coalesce(assigned_to, me.id),
         assigned_name  = coalesce(assigned_name, me.full_name),
         assigned_phone = coalesce(assigned_phone, me.phone),
         assigned_at    = coalesce(assigned_at, now()),
         resolution_note = case when p_next = 'resolved' then note else resolution_note end
   where id = inc.id
   returning * into inc;

  if note is not null and p_next <> 'resolved' then
    insert into public.incident_events (incident_id, society_id, actor_id, actor_name, action, note)
    values (inc.id, inc.society_id, me.id, me.full_name, 'note_added', note);
  end if;
  return inc;
end;
$$;

create or replace function public.cancel_incident(p_incident_id uuid, p_reason text default null)
returns public.incidents
language plpgsql security definer
set search_path = public
as $$
declare
  me  public.profiles%rowtype;
  inc public.incidents%rowtype;
  by_reporter boolean;
begin
  select * into me from public.profiles where id = auth.uid();
  if me.id is null then
    raise exception 'forbidden';
  end if;

  select * into inc from public.incidents
   where id = p_incident_id and society_id = me.society_id
   for update;
  if not found then
    raise exception 'not_found';
  end if;
  if not public.is_active_status(inc.status) then
    raise exception 'invalid' using detail = 'Incident is already closed';
  end if;

  if length(p_reason) > 1000 then raise exception 'invalid' using detail = 'Reason must be at most 1000 characters'; end if;
  by_reporter := inc.reporter_id = me.id and inc.status in ('pending', 'acknowledged');
  if not (by_reporter or me.role = 'admin') then
    raise exception 'forbidden' using detail = 'Only the reporter (before help is on the way) or an admin can cancel';
  end if;

  update public.incidents
     set status = 'cancelled',
         resolution_note = coalesce(nullif(trim(coalesce(p_reason, '')), ''),
                                    case when by_reporter then 'Cancelled by reporter' else 'Cancelled by admin' end)
   where id = inc.id
   returning * into inc;
  return inc;
end;
$$;

create or replace function public.update_incident_details(
  p_incident_id uuid,
  p_description text default null,
  p_location_note text default null,
  p_people_affected int default null,
  p_asset_id uuid default null
)
returns public.incidents
language plpgsql security definer
set search_path = public
as $$
declare
  me  public.profiles%rowtype;
  inc public.incidents%rowtype;
  a   public.assets%rowtype;
begin
  select * into me from public.profiles where id = auth.uid();
  if me.id is null then
    raise exception 'forbidden';
  end if;

  select * into inc from public.incidents
   where id = p_incident_id and society_id = me.society_id
   for update;
  if not found then
    raise exception 'not_found';
  end if;
  if inc.reporter_id <> me.id and me.role <> 'admin' then
    raise exception 'forbidden';
  end if;
  if not public.is_active_status(inc.status) then
    raise exception 'invalid' using detail = 'Incident is already closed';
  end if;

  if length(p_description) > 2000 or length(p_location_note) > 500 then raise exception 'invalid' using detail = 'Details are too long'; end if;
  if p_asset_id is not null then
    select * into a from public.assets where id = p_asset_id and society_id = me.society_id;
    if not found then
      raise exception 'invalid' using detail = 'Asset not in society';
    end if;
  end if;

  update public.incidents
     set description     = case when p_description   is null then description   else nullif(trim(p_description), '') end,
         location_note   = case when p_location_note is null then location_note else nullif(trim(p_location_note), '') end,
         people_affected = case when p_people_affected is null then people_affected
                                else least(500, greatest(1, p_people_affected)) end,
         asset_id        = coalesce(p_asset_id, asset_id),
         asset_name      = coalesce(a.name, asset_name),
         urgency_score   = public.compute_urgency(
                             hazard_weight, zone_tier,
                             case when p_people_affected is null then people_affected
                                  else least(500, greatest(1, p_people_affected)) end,
                             extract(epoch from (now() - created_at)) / 60.0, vulnerable)
   where id = inc.id
   returning * into inc;
  return inc;
end;
$$;

-- The current update_member body is preserved; r2_profile_assignment_guard enforces active-load and specialty rules for all callers.

create or replace function public.start_safety_check(
  p_scope public.safety_check_scope, p_zone_id uuid, p_message text, p_type public.incident_type default 'fire'
)
returns public.safety_checks
language plpgsql security definer
set search_path = public
as $$
declare
  me  public.profiles%rowtype;
  chk public.safety_checks%rowtype;
  msg text := nullif(trim(coalesce(p_message, '')), '');
begin
  select * into me from public.profiles where id = auth.uid();
  if me.id is null or me.role <> 'admin' then
    raise exception 'forbidden';
  end if;
  if msg is null or length(msg) > 300 then
    raise exception 'invalid' using detail = 'Message is required (max 300 characters)';
  end if;
  if p_scope = 'zone' then
    perform 1 from public.zones where id = p_zone_id and society_id = me.society_id;
    if not found then
      raise exception 'invalid' using detail = 'Zone not in society';
    end if;
  end if;

  perform pg_advisory_xact_lock(hashtextextended(me.society_id::text, 72));
  if exists(select 1 from public.safety_checks where society_id = me.society_id and ended_at is null) then
    raise exception 'invalid' using detail = 'End the current safety check first';
  end if;
  insert into public.safety_checks (society_id, scope, zone_id, incident_type, message, started_by)
  values (me.society_id, p_scope, case when p_scope = 'zone' then p_zone_id else null end,
          coalesce(p_type, 'fire'), msg, me.id)
  returning * into chk;
  return chk;
end;
$$;

create or replace function public.respond_safety_check(
  p_check_id uuid, p_status public.safety_response_status, p_note text default null
)
returns public.safety_check_responses
language plpgsql security definer
set search_path = public
as $$
declare
  me      public.profiles%rowtype;
  chk     public.safety_checks%rowtype;
  my_zone uuid;
  resp    public.safety_check_responses%rowtype;
begin
  select * into me from public.profiles where id = auth.uid();
  if me.id is null then
    raise exception 'forbidden';
  end if;

  select * into chk from public.safety_checks where id = p_check_id and society_id = me.society_id;
  if not found then
    raise exception 'not_found';
  end if;
  if chk.ended_at is not null then
    raise exception 'invalid' using detail = 'This safety check has ended';
  end if;
  if chk.scope = 'zone' and me.role = 'resident' then
    select zone_id into my_zone from public.flats where id = me.flat_id;
    if my_zone is distinct from chk.zone_id then
      raise exception 'forbidden' using detail = 'This safety check is for another tower';
    end if;
  end if;

  if length(p_note) > 1000 then raise exception 'invalid' using detail = 'Note must be at most 1000 characters'; end if;
  insert into public.safety_check_responses (check_id, society_id, profile_id, status, note)
  values (chk.id, chk.society_id, me.id, p_status, p_note)
  on conflict (check_id, profile_id) do update
    set status = excluded.status, note = excluded.note
  returning * into resp;
  return resp;
end;
$$;

create or replace function public.incidents_before_insert()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
declare
  prof public.profiles%rowtype;
  z    public.zones%rowtype;
  f    public.flats%rowtype;
  a    public.assets%rowtype;
  hw   int;
begin
  new.reporter_id := coalesce(auth.uid(), new.reporter_id);
  if new.reporter_id is null then
    raise exception 'forbidden' using detail = 'No reporter';
  end if;

  select * into prof from public.profiles where id = new.reporter_id;
  if not found or prof.society_id is null then
    raise exception 'forbidden' using detail = 'Reporter is not a member of a society';
  end if;

  new.id               := coalesce(new.id, gen_random_uuid());
  new.society_id       := prof.society_id;
  new.status           := 'pending';
  new.assigned_to      := null;
  new.assigned_name    := null;
  new.assigned_phone   := null;
  new.assigned_at      := null;
  new.acknowledged_at  := null;
  new.en_route_at      := null;
  new.on_scene_at      := null;
  new.resolved_at      := null;
  new.cancelled_at     := null;
  new.resolution_note  := null;
  new.escalation_level := 1;
  new.created_at       := now();
  new.updated_at       := now();
  if new.source = 'safety_check' and new.safety_check_id is null then
    new.source := 'app';
  end if;

  new.reporter_name          := prof.full_name;
  new.reporter_phone         := prof.phone;
  new.reporter_vulnerability := prof.vulnerability;
  new.vulnerable             := public.has_vulnerability(prof.vulnerability);

  new.description   := nullif(trim(coalesce(new.description, '')), '');
  new.location_note := nullif(trim(coalesce(new.location_note, '')), '');

  if new.flat_id is not null and new.flat_id is distinct from prof.flat_id then
    raise exception 'invalid' using detail = 'Only your own flat can be reported as home';
  end if;
  if length(new.location_note) > 500 then raise exception 'invalid' using detail = 'Location note must be at most 500 characters'; end if;
  -- No location given at all: assume the reporter's own flat.
  -- Only a spot note (e.g. the list of places failed to load): route to the reporter's tower,
  -- without their flat, so the alert still reaches someone and the note says where.
  if new.flat_id is null and new.zone_id is null then
    if new.location_note is null then
      new.flat_id := prof.flat_id;
    else
      select zone_id into new.zone_id from public.flats where id = prof.flat_id;
    end if;
  end if;
  if new.flat_id is not null then
    select * into f from public.flats where id = new.flat_id;
    if not found or f.society_id <> new.society_id then
      raise exception 'invalid' using detail = 'Flat not in society';
    end if;
    if new.zone_id is not null and new.zone_id <> f.zone_id then
      -- Zone wins (e.g. a tower-wide Safety Check); the flat is elsewhere.
      new.flat_id := null;
    else
      new.flat_label := f.number;
      new.zone_id := f.zone_id;
      new.floor   := coalesce(new.floor, f.floor);
    end if;
  end if;

  if new.zone_id is null then
    raise exception 'invalid' using detail = 'Location (zone) is required';
  end if;
  select * into z from public.zones where id = new.zone_id;
  if not found or z.society_id <> new.society_id then
    raise exception 'invalid' using detail = 'Zone not in society';
  end if;
  new.zone_name := z.name;
  new.zone_tier := z.tier;

  if new.asset_id is not null then
    select * into a from public.assets where id = new.asset_id;
    if not found or a.society_id <> new.society_id then
      raise exception 'invalid' using detail = 'Asset not in society';
    end if;
    new.asset_name := a.name;
  end if;

  select weight into hw from public.hazard_weights where society_id = new.society_id and type = new.type;
  new.hazard_weight   := coalesce(hw, public.default_hazard_weight(new.type));
  new.people_affected := least(500, greatest(1, coalesce(new.people_affected, 1)));
  new.urgency_score   := public.compute_urgency(new.hazard_weight, new.zone_tier, new.people_affected, 0, new.vulnerable);
  return new;
end;
$$;

create or replace function public.set_my_home(p_zone_id uuid, p_floor int, p_flat_number text)
returns public.profiles language plpgsql security definer set search_path = public as $$
declare me public.profiles%rowtype; z public.zones%rowtype; fl public.flats%rowtype; digits text; label text;
begin
  select * into me from public.profiles where id = auth.uid() for update;
  if me.id is null or me.society_id is null then raise exception 'forbidden'; end if;
  select * into z from public.zones where id = p_zone_id and society_id = me.society_id and kind = 'tower';
  if not found then raise exception 'invalid' using detail = 'Choose a tower in your society'; end if;
  digits := upper(trim(coalesce(p_flat_number,'')));
  label := upper(coalesce(nullif(z.code,''),left(z.name,1)));
  if digits like label || '-%' then digits := substr(digits, length(label)+2); end if;
  if digits !~ '^[0-9]{1,8}$' then raise exception 'invalid' using detail = 'Enter a valid flat number'; end if;
  label := label || '-' || digits;
  select * into fl from public.flats where zone_id=z.id and upper(number)=label for update;
  if not found then raise exception 'invalid' using detail = 'Flat not found. Ask your society admin'; end if;
  if p_floor is not null and p_floor is distinct from fl.floor then raise exception 'invalid' using detail = 'Floor does not match the flat'; end if;
  if exists(select 1 from public.profiles where flat_id=fl.id and id<>me.id) then
    raise exception 'invalid' using detail = 'This flat is occupied. Ask your society admin';
  end if;
  update public.profiles set flat_id=fl.id where id=me.id returning * into me;
  return me;
end $$;

create or replace function public.safety_check_responses_before_write()
returns trigger
language plpgsql security definer
set search_path = public
as $$
declare
  chk  public.safety_checks%rowtype;
  prof public.profiles%rowtype;
  zid  uuid;
  linked uuid;
begin
  select * into chk from public.safety_checks where id = new.check_id;
  if not found then
    raise exception 'not_found' using detail = 'Safety check not found';
  end if;
  new.society_id   := chk.society_id;
  new.responded_at := now();
  new.note := nullif(trim(coalesce(new.note, '')), '');

  if length(new.note) > 1000 then raise exception 'invalid' using detail = 'Note must be at most 1000 characters'; end if;
  perform pg_advisory_xact_lock(hashtextextended(new.check_id::text || ':' || new.profile_id::text, 73));
  select id into linked from public.incidents where safety_check_id=new.check_id and reporter_id=new.profile_id
    and source='safety_check' and public.is_active_status(status) order by created_at limit 1 for update;
  new.incident_id := linked;
  if new.status = 'safe' and linked is not null then
    update public.incidents set status='cancelled', resolution_note='Resident marked safe in safety check' where id=linked and status='pending';
    if not found then
      insert into public.incident_events(incident_id,society_id,actor_id,actor_name,action,note)
      select linked, chk.society_id, new.profile_id, full_name, 'note_added', 'Resident now reports safe. Confirm before closing.' from public.profiles where id=new.profile_id;
    end if;
  end if;
  if new.status = 'need_help' and new.incident_id is null then
    select * into prof from public.profiles where id = new.profile_id;
    zid := chk.zone_id;
    if zid is null and prof.flat_id is not null then
      select zone_id into zid from public.flats where id = prof.flat_id;
    end if;
    if zid is null then
      select id into zid from public.zones where society_id = chk.society_id order by sort_order, name limit 1;
    end if;

    insert into public.incidents
      (society_id, source, type, reporter_id, zone_id, flat_id, description, people_affected, safety_check_id)
    values
      (chk.society_id, 'safety_check', chk.incident_type, new.profile_id, zid, prof.flat_id,
       'Safety Check — needs help: ' || chk.message || coalesce(' · ' || new.note, ''), 1, chk.id)
    returning id into new.incident_id;
  end if;

  return new;
end;
$$;

create or replace function public.r2_members_guard() returns trigger
language plpgsql security definer set search_path=public as $$
begin
  perform pg_advisory_xact_lock(hashtextextended(old.society_id::text, 74));
  if tg_op='DELETE' or (tg_op='UPDATE' and (new.role<>old.role or new.phone<>old.phone)) then
    if old.profile_id=auth.uid() then raise exception 'invalid' using detail='You cannot remove your own admin role'; end if;
    if old.role='admin' and not exists(select 1 from public.members where society_id=old.society_id and role='admin' and id<>old.id) then
      raise exception 'invalid' using detail='Keep at least one approved admin';
    end if;
  end if;
  if tg_op='DELETE' then return old; end if;
  if length(trim(new.full_name))=0 or length(new.full_name)>100 then raise exception 'invalid' using detail='Name must be between 1 and 100 characters'; end if;
  if new.role='responder' and cardinality(new.specialties)=0 then raise exception 'invalid' using detail='Choose at least one specialty'; end if;
  return new;
end $$;
drop trigger if exists r2_members_guard on public.members;
create trigger r2_members_guard before update or delete on public.members for each row execute function public.r2_members_guard();
-- Insert validation is separate because OLD is not populated on INSERT.
create or replace function public.r2_member_insert_guard() returns trigger
language plpgsql set search_path=public as $$ begin
  if length(trim(new.full_name))=0 or length(new.full_name)>100 then raise exception 'invalid' using detail='Name must be between 1 and 100 characters'; end if;
  if new.role='responder' and coalesce(cardinality(new.specialties),0)=0 then raise exception 'invalid' using detail='Choose at least one specialty'; end if;
  return new;
end $$;
drop trigger if exists r2_member_insert_guard on public.members;
create trigger r2_member_insert_guard before insert on public.members for each row execute function public.r2_member_insert_guard();
-- Block future-dated mistakes; extending an expired notice remains possible.
create or replace function public.r2_notice_guard() returns trigger language plpgsql as $$ begin
  if new.expires_at is not null and new.expires_at<=now() then raise exception 'invalid' using detail='Expiry must be in the future'; end if; return new;
end $$;
drop trigger if exists r2_notice_guard on public.notices;
create trigger r2_notice_guard before insert or update of expires_at on public.notices for each row execute function public.r2_notice_guard();


revoke all on function public.r2_members_guard() from public, anon, authenticated;

revoke all on function public.r2_member_insert_guard() from public, anon, authenticated;

revoke all on function public.r2_notice_guard() from public, anon, authenticated;

revoke all on function public.incidents_before_insert() from public, anon, authenticated;

revoke all on function public.safety_check_responses_before_write() from public, anon, authenticated;

drop function public.assign_incident(uuid, uuid);
create or replace function public.assign_incident(p_incident_id uuid, p_responder_id uuid, p_expected_assignee uuid default null, p_check_expected boolean default false)
returns public.incidents
language plpgsql security definer
set search_path = public
as $$
declare
  me  public.profiles%rowtype;
  r   public.profiles%rowtype;
  inc public.incidents%rowtype;
begin
  select * into me from public.profiles where id = auth.uid();
  if me.id is null or me.role <> 'admin' then
    raise exception 'forbidden';
  end if;

  select * into inc from public.incidents
   where id = p_incident_id and society_id = me.society_id
   for update;
  if not found then
    raise exception 'not_found';
  end if;
  if not public.is_active_status(inc.status) then
    raise exception 'invalid' using detail = 'Incident is already closed';
  end if;

  if p_check_expected and inc.assigned_to is distinct from p_expected_assignee then
    raise exception 'invalid' using detail = 'Assignment changed. Refresh and try again';
  end if;
  select * into r from public.profiles
   where id = p_responder_id and society_id = me.society_id and role in ('responder', 'admin') for update;
  if not found then
    raise exception 'not_found' using detail = 'Responder not found in this society';
  end if;
  if r.role='responder' and not (inc.type=any(r.specialties)) then raise exception 'wrong_specialty'; end if;
  if not r.on_duty then raise exception 'not_on_duty'; end if;
  if inc.assigned_to = r.id then
    return inc;
  end if;

  update public.incidents
     set assigned_to = r.id, assigned_name = r.full_name, assigned_phone = r.phone, assigned_at = now(),
         status = 'acknowledged', acknowledged_at=now(), en_route_at=null, on_scene_at=null
   where id = inc.id
   returning * into inc;
  return inc;
end;
$$;
revoke all on function public.assign_incident(uuid,uuid,uuid,boolean) from public,anon;
grant execute on function public.assign_incident(uuid,uuid,uuid,boolean) to authenticated;


create or replace function public.incidents_before_update()
returns trigger
language plpgsql
set search_path = public
as $$
begin
  -- immutable
  new.id              := old.id;
  new.society_id      := old.society_id;
  new.reporter_id     := old.reporter_id;
  new.type            := old.type;
  new.client_id       := old.client_id;
  new.source          := old.source;
  new.safety_check_id := old.safety_check_id;
  new.created_at      := old.created_at;
  new.updated_at      := now();

  if new.status <> old.status then
    if not public.is_active_status(old.status) then
      raise exception 'invalid' using detail = 'Incident is already closed';
    end if;
    if new.assigned_to is not distinct from old.assigned_to and public.status_rank(new.status) <= public.status_rank(old.status) then
      raise exception 'invalid' using detail = 'Status can only move forward';
    end if;
    case new.status
      when 'acknowledged' then new.acknowledged_at := coalesce(new.acknowledged_at, now());
      when 'en_route'     then new.en_route_at     := coalesce(new.en_route_at, now());
      when 'on_scene'     then new.on_scene_at     := coalesce(new.on_scene_at, now());
      when 'resolved'     then new.resolved_at     := coalesce(new.resolved_at, now());
      when 'cancelled'    then new.cancelled_at    := coalesce(new.cancelled_at, now());
      else null;
    end case;
    -- a closed incident stops escalating
    if not public.is_active_status(new.status) then
      new.escalation_level := old.escalation_level;
    end if;
  end if;

  return new;
end;
$$;

create or replace function public.r2_flat_inventory_guard() returns trigger
language plpgsql set search_path=public as $$ begin
  if auth.uid() is not null then raise exception 'invalid' using detail='Flat not found. Ask your society admin'; end if;
  return new;
end $$;
drop trigger if exists r2_flat_inventory_guard on public.flats;
create trigger r2_flat_inventory_guard before insert on public.flats for each row execute function public.r2_flat_inventory_guard();
revoke all on function public.r2_flat_inventory_guard() from public,anon,authenticated;
-- Cover profile writes by newer member RPCs as well as update_member.
create or replace function public.r2_profile_assignment_guard() returns trigger
language plpgsql security definer set search_path=public as $$ begin
  if new.role='responder' and coalesce(cardinality(new.specialties),0)=0 then raise exception 'invalid' using detail='Choose at least one specialty'; end if;
  if (new.role is distinct from old.role or new.specialties is distinct from old.specialties) and exists (
    select 1 from public.incidents i where i.assigned_to=old.id and public.is_active_status(i.status)
      and (new.role='resident' or (new.role='responder' and not(i.type=any(new.specialties))))
  ) then raise exception 'invalid' using detail='Reassign active incidents before changing this member'; end if;
  return new;
end $$;
drop trigger if exists r2_profile_assignment_guard on public.profiles;
create trigger r2_profile_assignment_guard before update on public.profiles for each row execute function public.r2_profile_assignment_guard();
revoke all on function public.r2_profile_assignment_guard() from public,anon,authenticated;


notify pgrst, 'reload schema';
commit;
