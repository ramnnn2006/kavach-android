-- LOCAL DISPOSABLE DATABASE ONLY. Uses fixed seed IDs; never run against production.
\set ON_ERROR_STOP on
begin;
-- Everything in this script rolls back; no live backend is involved.
insert into public.societies(id,name) values ('11111111-1111-4111-8111-000000000001','[r2-fix]');
insert into public.zones(id,society_id,name,code,kind) values ('22222222-2222-4222-8222-000000000001','11111111-1111-4111-8111-000000000001','Tower A','A','tower');
insert into public.flats(id,society_id,zone_id,number,floor) values ('33333333-3333-4333-8333-000000000001','11111111-1111-4111-8111-000000000001','22222222-2222-4222-8222-000000000001','A-1204',12);
insert into public.members(society_id,phone,full_name,role,specialties) values
('11111111-1111-4111-8111-000000000001','+919000000001','Resident','resident','{}'),
('11111111-1111-4111-8111-000000000001','+919000000003','Responder','responder','{fire,water}'),
('11111111-1111-4111-8111-000000000001','+919000000005','Admin','admin','{}');
insert into auth.users(id,phone,email) values
('88888888-8888-4888-8888-000000000001','919000000001','test1@example.invalid'),
('88888888-8888-4888-8888-000000000003','919000000003','test3@example.invalid'),
('88888888-8888-4888-8888-000000000005','919000000005','test5@example.invalid');
update public.profiles set role='admin', on_duty=true where id='88888888-8888-4888-8888-000000000005';
update public.profiles set role='responder', specialties='{fire,water}', on_duty=true where id='88888888-8888-4888-8888-000000000003';
update public.profiles set flat_id='33333333-3333-4333-8333-000000000001' where id='88888888-8888-4888-8888-000000000001';
select set_config('request.jwt.claim.sub','88888888-8888-4888-8888-000000000001',true);
insert into public.incidents(id,type,people_affected) values ('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa','fire',4);
select public.update_incident_details('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',p_description=>'[r2-fix] note only');
do $$ begin
 if (select people_affected from public.incidents where id='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa')<>4 then raise exception 'people regression'; end if;
 begin insert into auth.users(id,email) values (gen_random_uuid(),'unapproved@example.invalid'); raise exception 'signup accepted'; exception when raise_exception then if sqlerrm<>'forbidden' then raise; end if; end;
 begin update public.incidents set location_note=repeat('x',501) where id='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'; raise exception 'long note accepted'; exception when check_violation then null; end;
end $$;
select set_config('request.jwt.claim.sub','88888888-8888-4888-8888-000000000003',true);
select public.claim_incident('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa');
select public.claim_incident('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa');
do $$ begin
 begin perform public.advance_incident('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa','resolved'); raise exception 'skip accepted'; exception when raise_exception then if sqlerrm<>'invalid' then raise; end if; end;
end $$;
select public.advance_incident('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa','en_route');
select set_config('request.jwt.claim.sub','88888888-8888-4888-8888-000000000005',true);
do $$ begin
 begin perform public.update_member('88888888-8888-4888-8888-000000000003','resident'); raise exception 'demotion accepted'; exception when raise_exception then if sqlerrm<>'invalid' then raise; end if; end;
end $$;
select public.start_safety_check('society',null,'[r2-fix] safety');
do $$ begin
 begin perform public.start_safety_check('society',null,'[r2-fix] duplicate'); raise exception 'duplicate check accepted'; exception when raise_exception then if sqlerrm<>'invalid' then raise; end if; end;
end $$;
select set_config('request.jwt.claim.sub','88888888-8888-4888-8888-000000000001',true);
select public.respond_safety_check((select id from public.safety_checks limit 1),'need_help');
select public.respond_safety_check((select id from public.safety_checks limit 1),'need_help');
do $$ begin
 if (select count(*) from public.incidents where source='safety_check')<>1 then raise exception 'help duplicates'; end if;
end $$;
select public.respond_safety_check((select id from public.safety_checks limit 1),'safe');
do $$ begin
 if exists(select 1 from public.incidents where source='safety_check' and public.is_active_status(status)) then raise exception 'safe left pending alert'; end if;
end $$;
rollback;
