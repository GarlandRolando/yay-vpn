import assert from 'node:assert/strict';
// Shared by disposable PostgreSQL CI and the local embedded PostgreSQL check.
export async function checkDeviceManagement(rpc) {
  const admin='device-tests-'+crypto.randomUUID(),password='pbkdf2$600000$fixture$fixture';
  await rpc('admin_login_create',{token_hash:admin});
  async function user(){const username='devices_'+crypto.randomUUID().slice(0,8);await rpc('admin_users_create',{token_hash:admin,username,password_hash:password,device_limit:2,expires_at:Math.floor(Date.now()/1000)+3600});return (await rpc('login_lookup',{username})).id;}
  async function login(id,key=crypto.randomUUID()){const token=crypto.randomUUID();const result=await rpc('login_finish',{user_id:id,verified_hash:password,public_key:key,device_name:'Fixture device',nonce_hash:crypto.randomUUID(),token_hash:token});return {...result,token,key};}
  const auth=d=>({token_hash:d.token,verified_public_key:d.key,nonce_hash:crypto.randomUUID()});
  const owner=await user(),other=await user(),first=await login(owner),second=await login(owner),foreign=await login(other);
  const list=await rpc('user_devices_list',auth(first));
  assert.equal(list.devices.length,2);assert.equal(list.current_device_id,first.device_id);assert.equal(list.devices[0].is_current,true);assert.equal(list.free_slots,0);
  assert.equal(list.account.device_limit,2);assert.ok(!JSON.stringify(list).includes(first.key));assert.ok(!JSON.stringify(list).includes(foreign.device_id));
  assert.equal((await rpc('user_bootstrap',auth(first))).current_device_id,first.device_id);
  assert.equal((await rpc('user_devices_delete',{...auth(first),id:foreign.device_id})).status,404);
  assert.ok((await rpc('user_context',{token_hash:foreign.token})).public_key);
  assert.equal((await rpc('user_devices_delete',{...auth(first),id:first.device_id})).status,409);
  const wrongKey={...auth(first),verified_public_key:'not-the-device'};
  assert.equal((await rpc('user_devices_delete',{...wrongKey,id:second.device_id})).status,401);
  const replay=auth(first);assert.equal((await rpc('user_devices_list',replay)).devices.length,2);assert.equal((await rpc('user_devices_list',replay)).status,401);
  const removed=await rpc('user_devices_delete',{...auth(first),id:second.device_id});
  assert.equal(removed.free_slots,1);assert.equal(removed.account.devices_used,1);assert.equal(removed.devices.length,1);
  assert.equal((await rpc('user_context',{token_hash:second.token})).status,401);
  assert.equal((await rpc('user_bootstrap',auth(second))).status,401);
  assert.ok((await rpc('user_context',{token_hash:first.token})).public_key);
  assert.ok((await login(owner)).account);assert.equal((await login(owner)).status,409);
  assert.equal((await rpc('user_devices_delete',{...auth(first),id:second.device_id})).status,404);
}
