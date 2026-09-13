// Run only against the disposable CI database named yay_test. Never against your Supabase project.
import test from 'node:test';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
if(process.env.YAY_PG_TEST!=='1'||process.env.PGDATABASE!=='yay_test')throw Error('Use a disposable database named yay_test and set YAY_PG_TEST=1.');
function sql(text){return new Promise((accept,reject)=>{const p=spawn('psql',['-X','-A','-t','-v','ON_ERROR_STOP=1'],{stdio:['pipe','pipe','pipe']});let out='',error='';p.stdout.on('data',b=>out+=b);p.stderr.on('data',b=>error+=b);p.on('error',reject);p.on('exit',code=>code===0?accept(out.trim()):reject(Error(error)));p.stdin.end(text);});}
const quote=s=>"'"+s.replaceAll("'","''")+"'";
async function rpc(a,p={}){return JSON.parse(await sql(`select public.yay_rpc(${quote(a)},${quote(JSON.stringify(p))}::jsonb);`));}
const admin='a'.repeat(64),password='pbkdf2$600000$fixture$fixture';
async function fixture(limit=2){
 await rpc('admin_login_create',{token_hash:admin});
 const username='test_'+crypto.randomUUID().slice(0,8);await rpc('admin_users_create',{token_hash:admin,username,password_hash:password,device_limit:limit,expires_at:Math.floor(Date.now()/1000)+3600});
 const u=await rpc('login_lookup',{username});return u.id;
}
async function login(id,key='device-'+crypto.randomUUID(),nonce=crypto.randomUUID()){
 const token=crypto.randomUUID();const result=await rpc('login_finish',{user_id:id,verified_hash:password,public_key:key,device_name:'CI fixture',nonce_hash:nonce,token_hash:token});return {...result,token,key};
}
function auth(d){return {token_hash:d.token,verified_public_key:d.key,nonce_hash:crypto.randomUUID()};}

test('Anonymous/authenticated roles cannot execute RPC or use private schema',async()=>{
 assert.equal(await sql("select has_function_privilege('anon','public.yay_rpc(text,jsonb)','execute');"),'f');
 assert.equal(await sql("select has_function_privilege('authenticated','public.yay_rpc(text,jsonb)','execute');"),'f');
 assert.equal(await sql("select has_function_privilege('service_role','public.yay_rpc(text,jsonb)','execute');"),'t');
 assert.equal(await sql("select has_schema_privilege('anon','yay_private','usage');"),'f');
 assert.equal(await sql("select count(*) from pg_class c join pg_namespace n on c.relnamespace=n.oid where n.nspname='yay_private' and c.relkind='r' and not c.relrowsecurity;"),'0');
});
test('Five simultaneous logins cannot overfill one device slot',async()=>{
 const id=await fixture(1);const results=await Promise.all(Array.from({length:5},()=>login(id)));assert.equal(results.filter(x=>x.account).length,1);assert.equal(results.filter(x=>x.status===409).length,4);
});
test('Existing device login rotates sessions without using a new slot',async()=>{
 const id=await fixture(1),d=await login(id),next=await login(id,d.key);assert.ok(next.account);assert.equal(next.account.devices_used,1);assert.equal((await rpc('user_context',{token_hash:d.token})).status,401);assert.ok((await rpc('user_context',{token_hash:next.token})).public_key);
});
test('Replay rejected; logout keeps registration; admin removal frees slot',async()=>{
 const id=await fixture(1),d=await login(id),payload=auth(d);assert.ok((await rpc('user_bootstrap',payload)).account);assert.equal((await rpc('user_bootstrap',payload)).status,401);
 await rpc('user_logout',auth(d));assert.equal((await rpc('user_context',{token_hash:d.token})).status,401);assert.equal((await login(id)).status,409);
 await rpc('admin_devices_delete',{token_hash:admin,id:d.device_id});assert.ok((await login(id)).account);
});
test('Password-reset race cannot register using an obsolete password hash',async()=>{
 const id=await fixture();await rpc('admin_users_update',{token_hash:admin,id,password_hash:'new-password-hash'});assert.equal((await login(id)).status,401);
});
test('Expiry, paused account and password reset revoke access',async()=>{
 const id=await fixture(),d=await login(id);await rpc('admin_users_update',{token_hash:admin,id,expires_at:1});assert.equal((await rpc('user_bootstrap',auth(d))).status,403);
 await rpc('admin_users_update',{token_hash:admin,id,expires_at:Math.floor(Date.now()/1000)+3600,password_hash:'replacement'});assert.equal((await rpc('user_context',{token_hash:d.token})).status,401);
});
test('Server revisions and disabling are enforced; ciphertext stays out of catalog',async()=>{
 const id=await fixture(),d=await login(id),name='Node '+crypto.randomUUID();await rpc('admin_servers_create',{token_hash:admin,name,location:'SG',protocol:'trojan',config_enc:'encrypted-fixture'});
 const servers=(await rpc('admin_servers_list',{token_hash:admin})).servers,s=servers.find(s=>s.name===name);assert.equal(s.config_enc,undefined);
 const grant=await rpc('user_connect',{...auth(d),server_id:s.id});assert.equal(grant.config_enc,'encrypted-fixture');assert.ok(grant.lease_seconds<=180);
 await rpc('admin_servers_update',{token_hash:admin,id:s.id,name:'Renamed'});assert.equal((await rpc('user_heartbeat',{...auth(d),server_id:s.id,revision:1})).status,409);
 await rpc('admin_servers_update',{token_hash:admin,id:s.id,enabled:0});assert.equal((await rpc('user_connect',{...auth(d),server_id:s.id})).status,404);
});
test('A valid app session cannot call admin RPC actions',async()=>{const d=await login(await fixture());assert.equal((await rpc('admin_users_list',{token_hash:d.token})).status,401);});
