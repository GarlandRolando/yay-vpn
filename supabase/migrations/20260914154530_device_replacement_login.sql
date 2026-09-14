-- Allow a correctly authenticated new device to replace one of its account's
-- registered devices when the device limit is full. The Edge Function verifies
-- the username/password and signed device request before calling this function.
begin;

create or replace function public.yay_login_replace(p_payload jsonb default '{}'::jsonb) returns jsonb
language plpgsql security definer set search_path='' as $$
declare
  t bigint := extract(epoch from clock_timestamp())::bigint;
  u yay_private.users%rowtype;
  d yay_private.devices%rowtype;
  a jsonb;
  count_now integer;
  target uuid;
  token_value text := p_payload->>'token_hash';
begin
  select * into u from yay_private.users
  where id=(p_payload->>'user_id')::uuid
  for update;

  if not found or u.password_hash is distinct from p_payload->>'verified_hash' then
    return '{"status":401,"error":"Incorrect username or password"}'::jsonb;
  end if;
  if u.enabled<>1 or u.expires_at<=t then
    return '{"status":403,"error":"Your access has expired or is paused."}'::jsonb;
  end if;

  -- List mode is reached only after the normal login transaction has already
  -- verified and consumed the signed request nonce, then reported a full limit.
  if nullif(p_payload->>'replace_device_id','') is null then
    select count(*) into count_now from yay_private.devices where user_id=u.id;
    if count_now<u.device_limit then
      return '{"status":409,"error":"A device slot is now available. Try signing in again."}'::jsonb;
    end if;
    select coalesce(jsonb_agg(jsonb_build_object(
      'id',dv.id,
      'name',dv.name,
      'created_at',dv.created_at,
      'last_seen',dv.last_seen
    ) order by dv.last_seen desc,dv.created_at,dv.id),'[]'::jsonb)
    into a from yay_private.devices dv where dv.user_id=u.id;
    return jsonb_build_object(
      'requires_device_replacement',true,
      'devices',a,
      'device_limit',u.device_limit
    );
  end if;

  -- Replacement mode is a fresh signed login request, so consume its nonce here.
  delete from yay_private.nonces where expires_at<t;
  insert into yay_private.nonces values(p_payload->>'nonce_hash',t+180) on conflict do nothing;
  if not found then
    return '{"status":401,"error":"This request was already used."}'::jsonb;
  end if;

  -- A concurrent login may have registered this same device while the chooser
  -- was open. In that case simply issue a fresh session rather than kicking anyone.
  select * into d from yay_private.devices
  where user_id=u.id and public_key=p_payload->>'public_key'
  for update;

  if not found then
    target=(p_payload->>'replace_device_id')::uuid;
    delete from yay_private.devices where id=target and user_id=u.id;
    if not found then
      return '{"status":404,"error":"That device is no longer registered. Choose another device."}'::jsonb;
    end if;

    select count(*) into count_now from yay_private.devices where user_id=u.id;
    if count_now>=u.device_limit then
      return '{"status":409,"error":"Device limit changed. Choose a device again."}'::jsonb;
    end if;

    insert into yay_private.devices(user_id,public_key,name,created_at,last_seen)
    values(u.id,p_payload->>'public_key',left(p_payload->>'device_name',80),t,t)
    returning * into d;
  else
    update yay_private.devices
    set last_seen=t,name=left(p_payload->>'device_name',80)
    where id=d.id returning * into d;
  end if;

  delete from yay_private.sessions where device_id=d.id;
  insert into yay_private.sessions values(token_value,d.id,least(u.expires_at,t+2592000));
  return jsonb_build_object('account',yay_private.account(u.id),'device_id',d.id);
exception
  when invalid_text_representation or check_violation or not_null_violation then
    return '{"status":400,"error":"Invalid request values."}'::jsonb;
end;
$$;

revoke all on function public.yay_login_replace(jsonb) from public,anon,authenticated;
grant execute on function public.yay_login_replace(jsonb) to service_role;
notify pgrst,'reload schema';
commit;
