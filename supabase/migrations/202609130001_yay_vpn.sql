-- Yay VPN / Supabase v1. Run on a NEW project or an existing project without a yay_private schema.
-- Idempotent for this version. No tables are exposed to app/browser clients.
begin;
create schema if not exists yay_private;
revoke all on schema yay_private from public, anon, authenticated;
create table if not exists yay_private.users (
 id uuid primary key default gen_random_uuid(), username text not null,
 password_hash text not null, device_limit integer not null check(device_limit between 1 and 100),
 expires_at bigint not null, enabled integer not null default 1 check(enabled in(0,1)),
 created_at bigint not null default extract(epoch from now())::bigint
);
create unique index if not exists yay_username_unique on yay_private.users(lower(username));
create table if not exists yay_private.devices (
 id uuid primary key default gen_random_uuid(), user_id uuid not null references yay_private.users(id) on delete cascade,
 public_key text not null, name text not null, created_at bigint not null, last_seen bigint not null,
 unique(user_id,public_key)
);
create table if not exists yay_private.sessions (
 token_hash text primary key, device_id uuid not null references yay_private.devices(id) on delete cascade, expires_at bigint not null
);
create table if not exists yay_private.servers (
 id uuid primary key default gen_random_uuid(), name text not null, location text not null default '',protocol text not null,
 config_enc text not null,enabled integer not null default 1 check(enabled in(0,1)),revision integer not null default 1,
 created_at bigint not null default extract(epoch from now())::bigint
);
create table if not exists yay_private.nonces (key text primary key,expires_at bigint not null);
create table if not exists yay_private.admin_sessions (token_hash text primary key,expires_at bigint not null);
create table if not exists yay_private.rate_limits (key text primary key,count integer not null,until_at bigint not null);
create index if not exists yay_sessions_device on yay_private.sessions(device_id);
create index if not exists yay_devices_user on yay_private.devices(user_id);
create index if not exists yay_nonces_expiry on yay_private.nonces(expires_at);
create index if not exists yay_rates_expiry on yay_private.rate_limits(until_at);
create index if not exists yay_sessions_expiry on yay_private.sessions(expires_at);
alter table yay_private.users enable row level security;
alter table yay_private.devices enable row level security;
alter table yay_private.sessions enable row level security;
alter table yay_private.servers enable row level security;
alter table yay_private.nonces enable row level security;
alter table yay_private.admin_sessions enable row level security;
alter table yay_private.rate_limits enable row level security;
revoke all on all tables in schema yay_private from public,anon,authenticated;

create or replace function yay_private.catalog(p_admin boolean default false) returns jsonb
language sql set search_path='' as $$
 select coalesce(jsonb_agg(jsonb_build_object('id',id,'name',name,'location',location,'protocol',protocol,'enabled',enabled,'revision',revision) order by name),'[]'::jsonb)
 from yay_private.servers where p_admin or enabled=1;
$$;
create or replace function yay_private.account(p_id uuid) returns jsonb
language sql set search_path='' as $$
 select jsonb_build_object('username',username,'expires_at',expires_at,'device_limit',device_limit,
 'devices_used',(select count(*) from yay_private.devices d where d.user_id=u.id),'server_time',extract(epoch from clock_timestamp())::bigint)
 from yay_private.users u where id=p_id;
$$;

-- Only the Edge Function's server-side service role can call this entrypoint.
create or replace function public.yay_rpc(p_action text,p_payload jsonb default '{}'::jsonb) returns jsonb
language plpgsql security definer set search_path='' as $$
declare
 t bigint := extract(epoch from clock_timestamp())::bigint;
 u yay_private.users%rowtype;
 d yay_private.devices%rowtype;
 s yay_private.servers%rowtype;
 a jsonb;
 key_name text;
 count_now integer;
 uid uuid;
 did uuid;
 target uuid;
 limit_value integer;
 expiry_value bigint;
 enabled_value integer;
 password_value text;
 token_value text := p_payload->>'token_hash';
begin
 -- Bounded cleanup is opportunistic; no paid cron service required.
 if p_action='login_lookup' or p_action='admin_login_create' then
   delete from yay_private.sessions where expires_at<t;
   delete from yay_private.admin_sessions where expires_at<t;
 end if;
 if p_action='rate' then
   delete from yay_private.rate_limits where until_at<t;
   -- A global limit cannot be bypassed by spoofing an IP header; account limits are additional.
   foreach key_name in array array['global:'||(t/300)::text, 'name:'||coalesce(p_payload->>'name','')||':'||(t/300)::text] loop
     insert into yay_private.rate_limits(key,count,until_at) values(key_name,1,(t/300+1)*300)
     on conflict(key) do update set count=yay_private.rate_limits.count+1 returning count into count_now;
     if count_now > (case when key_name like 'global:%' then 100 else 15 end) then
       return jsonb_build_object('status',429,'error','Too many sign-in attempts. Please wait five minutes.');
     end if;
   end loop;
   return '{"ok":true}'::jsonb;
 end if;
 if p_action='login_lookup' then
   select * into u from yay_private.users where lower(username)=lower(p_payload->>'username');
   if not found then return '{}'::jsonb;end if;
   return jsonb_build_object('id',u.id,'password_hash',u.password_hash);
 end if;
 if p_action='login_finish' then
   select * into u from yay_private.users where id=(p_payload->>'user_id')::uuid for update;
   if not found or u.password_hash is distinct from p_payload->>'verified_hash' then return '{"status":401,"error":"Incorrect username or password"}'::jsonb;end if;
   if u.enabled<>1 or u.expires_at<=t then return '{"status":403,"error":"Your access has expired or is paused."}'::jsonb;end if;
   delete from yay_private.nonces where expires_at<t;
   insert into yay_private.nonces values(p_payload->>'nonce_hash',t+180) on conflict do nothing;
   if not found then return '{"status":401,"error":"This request was already used."}'::jsonb;end if;
   select * into d from yay_private.devices where user_id=u.id and public_key=p_payload->>'public_key';
   if not found then
     select count(*) into count_now from yay_private.devices where user_id=u.id;
     if count_now>=u.device_limit then return '{"status":409,"error":"Device limit reached. Ask your administrator to remove an old device."}'::jsonb;end if;
     insert into yay_private.devices(user_id,public_key,name,created_at,last_seen)
     values(u.id,p_payload->>'public_key',left(p_payload->>'device_name',80),t,t) returning * into d;
   else update yay_private.devices set last_seen=t where id=d.id;
   end if;
   delete from yay_private.sessions where device_id=d.id;
   insert into yay_private.sessions values(token_value,d.id,least(u.expires_at,t+2592000));
   return jsonb_build_object('account',yay_private.account(u.id),'device_id',d.id);
 end if;
 if p_action='user_context' then
   select dv.public_key into key_name from yay_private.sessions se join yay_private.devices dv on dv.id=se.device_id
   join yay_private.users us on us.id=dv.user_id where se.token_hash=token_value and se.expires_at>t and us.enabled=1 and us.expires_at>t;
   if not found then return '{"status":401,"error":"Your session has ended or access has expired. Sign in again."}'::jsonb;end if;
   return jsonb_build_object('public_key',key_name);
 end if;
 if p_action='admin_login_create' then
   insert into yay_private.admin_sessions values(token_value,t+3600);
   return '{"ok":true}'::jsonb;
 end if;
 if p_action like 'user_%' then
   select us.* into u from yay_private.sessions se join yay_private.devices dv on dv.id=se.device_id
   join yay_private.users us on us.id=dv.user_id where se.token_hash=token_value and se.expires_at>t for update of us;
   if not found then return '{"status":401,"error":"Your session has ended. Sign in again."}'::jsonb;end if;
   if u.enabled<>1 or u.expires_at<=t then return '{"status":403,"error":"Your access has expired or is paused."}'::jsonb;end if;
   select dv.* into d from yay_private.sessions se join yay_private.devices dv on dv.id=se.device_id where se.token_hash=token_value for update of dv;
   -- Recheck the same public key verified by the Edge Function, after locking account/device state.
   if not found or d.public_key is distinct from p_payload->>'verified_public_key' then return '{"status":401,"error":"Device session changed. Sign in again."}'::jsonb;end if;
   delete from yay_private.nonces where expires_at<t;
   insert into yay_private.nonces values(p_payload->>'nonce_hash',t+180) on conflict do nothing;
   if not found then return '{"status":401,"error":"This request was already used."}'::jsonb;end if;
   update yay_private.devices set last_seen=t where id=d.id;
   if p_action='user_bootstrap' then return jsonb_build_object('account',yay_private.account(u.id),'servers',yay_private.catalog());end if;
   if p_action='user_logout' then delete from yay_private.sessions where device_id=d.id;return '{"ok":true}'::jsonb;end if;
   if p_action in('user_connect','user_heartbeat') then
     select * into s from yay_private.servers where id=(p_payload->>'server_id')::uuid and enabled=1;
     if not found then return '{"status":404,"error":"This server is unavailable. Choose another server."}'::jsonb;end if;
     if p_action='user_heartbeat' and s.revision is distinct from (p_payload->>'revision')::integer then return '{"status":409,"error":"Server settings changed. Reconnect to update."}'::jsonb;end if;
     a=jsonb_build_object('lease_seconds',least(180,u.expires_at-t),'expires_at',u.expires_at,'server_time',t,'revision',s.revision);
     if p_action='user_connect' then a=a||jsonb_build_object('config_enc',s.config_enc);end if;
     return a;
   end if;
 end if;
 if p_action like 'admin_%' then
   perform 1 from yay_private.admin_sessions where token_hash=token_value and expires_at>t;
   if not found then return '{"status":401,"error":"Admin session expired. Sign in again."}'::jsonb;end if;
   if p_action='admin_context' then return '{"ok":true}'::jsonb;end if;
   if p_action='admin_logout' then delete from yay_private.admin_sessions where token_hash=token_value;return '{"ok":true}'::jsonb;end if;
   if p_action='admin_users_list' then
     select coalesce(jsonb_agg(jsonb_build_object('id',us.id,'username',us.username,'device_limit',us.device_limit,'expires_at',us.expires_at,'enabled',us.enabled,
     'devices_used',(select count(*) from yay_private.devices dv where dv.user_id=us.id)) order by us.created_at desc),'[]'::jsonb) into a from yay_private.users us;
     return jsonb_build_object('users',a);
   end if;
   if p_action='admin_users_create' then
     insert into yay_private.users(username,password_hash,device_limit,expires_at) values(p_payload->>'username',p_payload->>'password_hash',(p_payload->>'device_limit')::integer,(p_payload->>'expires_at')::bigint);
     return '{"ok":true}'::jsonb;
   end if;
   if p_action='admin_users_update' then
     target=(p_payload->>'id')::uuid;select * into u from yay_private.users where id=target for update;
     if not found then return '{"status":404,"error":"User not found"}'::jsonb;end if;
     limit_value=coalesce((p_payload->>'device_limit')::integer,u.device_limit);
     select count(*) into count_now from yay_private.devices where user_id=u.id;
     if limit_value<count_now then return '{"status":409,"error":"Remove old devices before lowering the limit."}'::jsonb;end if;
     password_value=coalesce(p_payload->>'password_hash',u.password_hash);enabled_value=coalesce((p_payload->>'enabled')::integer,u.enabled);
     update yay_private.users set device_limit=limit_value,expires_at=coalesce((p_payload->>'expires_at')::bigint,u.expires_at),enabled=enabled_value,password_hash=password_value where id=u.id;
     if enabled_value=0 or password_value<>u.password_hash then delete from yay_private.sessions where device_id in(select id from yay_private.devices where user_id=u.id);end if;
     return '{"ok":true}'::jsonb;
   end if;
   if p_action='admin_users_delete' then delete from yay_private.users where id=(p_payload->>'id')::uuid;return '{"ok":true}'::jsonb;end if;
   if p_action='admin_devices_list' then
     select coalesce(jsonb_agg(jsonb_build_object('id',id,'name',name,'created_at',created_at,'last_seen',last_seen) order by created_at),'[]'::jsonb) into a from yay_private.devices where user_id=(p_payload->>'id')::uuid;
     return jsonb_build_object('devices',a);
   end if;
   if p_action='admin_devices_delete' then delete from yay_private.devices where id=(p_payload->>'id')::uuid;return '{"ok":true}'::jsonb;end if;
   if p_action='admin_servers_list' then return jsonb_build_object('servers',yay_private.catalog(true));end if;
   if p_action='admin_servers_create' then
     insert into yay_private.servers(name,location,protocol,config_enc) values(p_payload->>'name',p_payload->>'location',p_payload->>'protocol',p_payload->>'config_enc');return '{"ok":true}'::jsonb;
   end if;
   if p_action='admin_servers_update' then
     update yay_private.servers set name=coalesce(p_payload->>'name',name),location=coalesce(p_payload->>'location',location),enabled=coalesce((p_payload->>'enabled')::integer,enabled),
     protocol=coalesce(p_payload->>'protocol',protocol),config_enc=coalesce(p_payload->>'config_enc',config_enc),revision=revision+1 where id=(p_payload->>'id')::uuid;
     if not found then return '{"status":404,"error":"Server not found"}'::jsonb;end if;return '{"ok":true}'::jsonb;
   end if;
   if p_action='admin_servers_delete' then delete from yay_private.servers where id=(p_payload->>'id')::uuid;return '{"ok":true}'::jsonb;end if;
   if p_action='admin_seed' then
     select coalesce(jsonb_agg(jsonb_build_object('id',id,'config_enc',config_enc)),'[]'::jsonb) into a from yay_private.servers where enabled=1;
     return jsonb_build_object('servers',yay_private.catalog(),'profiles',a,'generated_at',t);
   end if;
 end if;
 return '{"status":404,"error":"Endpoint not found"}'::jsonb;
exception
 when unique_violation then return '{"status":409,"error":"This username or record already exists."}'::jsonb;
 when invalid_text_representation or check_violation or not_null_violation then return '{"status":400,"error":"Invalid request values."}'::jsonb;
end;
$$;
revoke all on function public.yay_rpc(text,jsonb) from public,anon,authenticated;
grant execute on function public.yay_rpc(text,jsonb) to service_role;
revoke all on all functions in schema yay_private from public,anon,authenticated;
comment on function public.yay_rpc(text,jsonb) is 'Yay VPN: server-only RPC. Never grant execute to anon/authenticated.';
notify pgrst,'reload schema';
commit;
