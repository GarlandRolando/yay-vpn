import test from 'node:test';
import assert from 'node:assert/strict';
import { generateKeyPairSync,sign } from 'node:crypto';
import { createHandler } from '../supabase/functions/yay-api/handler.mjs';
import * as S from '../supabase/functions/yay-api/security.mjs';
const origin='https://abcdefghijklmnopqrst.supabase.co/functions/v1/yay-api';
const token='a'.repeat(43),serverID='11111111-2222-4333-8444-555555555555';
const vault=new S.Vault(S.base64(new Uint8Array(32).fill(7)));
const adminHash=await S.hashPassword('Administrator-password-2026');
async function req(handler,path,{method='GET',body,headers={}}={}){return handler(new Request(origin+path,{method,headers:{'content-type':'application/json',...headers},body:body===undefined?undefined:JSON.stringify(body)}));}

test('Health, CORS, unknown routes and body limits',async()=>{
 const handler=createHandler({rpc:async()=>{throw Error('Should not call DB');},vault,adminHash});
 assert.equal((await req(handler,'/healthz')).status,200);
 assert.equal((await req(handler,'/healthz',{headers:{origin:'https://evil.example'}})).status,403);
 const allowed=await req(handler,'/healthz',{headers:{origin:'http://127.0.0.1:8788'}});assert.equal(allowed.headers.get('Access-Control-Allow-Origin'),'http://127.0.0.1:8788');
 assert.equal((await req(handler,'/admin/login',{method:'POST',body:{password:'x'.repeat(40000)}})).status,413);
 assert.equal((await req(handler,'/unknown')).status,404);
});
test('Admin login hashes stored token and never returns backend secrets',async()=>{
 const calls=[];const handler=createHandler({rpc:async(a,p)=>{calls.push([a,p]);return {ok:true};},vault,adminHash});
 const denied=await req(handler,'/admin/login',{method:'POST',body:{password:'wrong'}});assert.equal(denied.status,401);
 const response=await req(handler,'/admin/login',{method:'POST',body:{password:'Administrator-password-2026'}});assert.equal(response.status,200);const data=await response.json();
 assert.equal(Object.keys(data).join(','),'token');const stored=calls.find(([a])=>a==='admin_login_create')[1].token_hash;assert.equal(stored,await S.sha(data.token));assert.notEqual(stored,data.token);
});
test('Admin authorization happens before expensive user creation',async()=>{
 const calls=[];const handler=createHandler({rpc:async(a,p)=>{calls.push(a);return {status:401,error:'denied'};},vault,adminHash});
 assert.equal((await req(handler,'/admin/users',{method:'POST',body:{username:'test',password:'Secret-password-2026'},headers:{Authorization:'Bearer '+token}})).status,401);
 assert.deepEqual(calls,['admin_context']);
});
test('Creating a server encrypts its credentials before passing data to PostgreSQL',async()=>{
 let saved;const handler=createHandler({rpc:async(a,p)=>{if(a==='admin_servers_create')saved=p;return {ok:true};},vault,adminHash});
 const result=await req(handler,'/admin/servers',{method:'POST',headers:{Authorization:'Bearer '+token},body:{name:'Singapore',location:'SG',uri:'trojan://SECRET-TEST-PASSWORD@example.com:443'}});
 assert.equal(result.status,200);assert.ok(saved.config_enc);assert.ok(!JSON.stringify(saved).includes('SECRET-TEST-PASSWORD'));assert.equal((await vault.open(saved.config_enc)).password,'SECRET-TEST-PASSWORD');
});
test('Device login translates Android signatures and handles SQL device-limit rejection',async()=>{
 const pair=generateKeyPairSync('ec',{namedCurve:'prime256v1'}),publicKey=S.base64(pair.publicKey.export({type:'spki',format:'der'}));
 const userHash=await S.hashPassword('User-password-2026');let finish;
 const handler=createHandler({rpc:async(a,p)=>{if(a==='login_lookup')return {id:serverID,password_hash:userHash};if(a==='login_finish'){finish=p;return {status:409,error:'Device limit reached'};}return {ok:true};},vault,adminHash});
 const body={username:'garland',password:'User-password-2026',public_key:publicKey,device_name:'Test phone'},path='/v1/login',raw=S.bytes(JSON.stringify(body));
 const ts=String(Math.floor(Date.now()/1000)),nonce=crypto.randomUUID(),message=['POST',path,ts,nonce,await S.sha(raw)].join('\n');
 const response=await req(handler,path,{method:'POST',body,headers:{'x-yay-time':ts,'x-yay-nonce':nonce,'x-yay-signature':S.base64(sign('sha256',Buffer.from(message),pair.privateKey))}});
 assert.equal(response.status,409);assert.equal(finish.public_key,publicKey);assert.equal(finish.verified_hash,userHash);assert.ok(!Object.hasOwn(finish,'password'));
});
test('Connect verifies device, decrypts only authorized config and removes ciphertext wrapper',async()=>{
 const pair=generateKeyPairSync('ec',{namedCurve:'prime256v1'}),publicKey=S.base64(pair.publicKey.export({type:'spki',format:'der'}));
 const enc=await vault.seal({type:'trojan',tag:'proxy',server:'example.com',server_port:443,password:'test',tls:{enabled:true}});let authorized;
 const handler=createHandler({rpc:async(a,p)=>{if(a==='user_context')return {public_key:publicKey};if(a==='user_connect'){authorized=p;return {config_enc:enc,lease_seconds:180,revision:1};}throw Error(a);},vault,adminHash});
 const path='/v1/connect',body={server_id:serverID,token_hash:'forged'},raw=S.bytes(JSON.stringify(body));const ts=String(Math.floor(Date.now()/1000)),nonce=crypto.randomUUID();
 const sig=sign('sha256',Buffer.from(['POST',path,ts,nonce,await S.sha(raw)].join('\n')),pair.privateKey);
 const result=await req(handler,path,{method:'POST',body,headers:{Authorization:'Bearer '+token,'x-yay-time':ts,'x-yay-nonce':nonce,'x-yay-signature':S.base64(sig)}});
 assert.equal(result.status,200);const json=await result.json();assert.equal(json.config.outbounds[0].password,'test');assert.equal(json.config_enc,undefined);assert.equal(authorized.token_hash,await S.sha(token));
});
test('A token without a valid device signature cannot reach connect dispatch',async()=>{
 const handler=createHandler({rpc:async(a,p)=>{assert.equal(a,'user_context');return {public_key:'bad'};},vault,adminHash});
 assert.equal((await req(handler,'/v1/connect',{method:'POST',body:{server_id:serverID},headers:{Authorization:'Bearer '+token}})).status,401);
});

test('User device routes authenticate signed paths and validate target IDs',async()=>{
 const pair=generateKeyPairSync('ec',{namedCurve:'prime256v1'}),publicKey=S.base64(pair.publicKey.export({type:'spki',format:'der'}));
 const calls=[];const handler=createHandler({rpc:async(a,p)=>{calls.push([a,p]);if(a==='user_context')return {public_key:publicKey};return {devices:[],free_slots:1};},vault,adminHash});
 async function signed(method,path){const ts=String(Math.floor(Date.now()/1000)),nonce=crypto.randomUUID(),raw=new Uint8Array();const sig=sign('sha256',Buffer.from([method,path,ts,nonce,await S.sha(raw)].join('\n')),pair.privateKey);return req(handler,path,{method,headers:{Authorization:'Bearer '+token,'x-yay-time':ts,'x-yay-nonce':nonce,'x-yay-signature':S.base64(sig)}});}
 assert.equal((await signed('GET','/v1/devices')).status,200);assert.equal(calls.at(-1)[0],'user_devices_list');
 assert.equal((await signed('DELETE','/v1/devices/'+serverID)).status,200);assert.equal(calls.at(-1)[0],'user_devices_delete');assert.equal(calls.at(-1)[1].id,serverID);
 const before=calls.length;assert.equal((await signed('DELETE','/v1/devices/not-a-uuid')).status,400);assert.equal(calls.length,before+1);
 assert.equal((await req(handler,'/v1/devices')).status,401);
});
