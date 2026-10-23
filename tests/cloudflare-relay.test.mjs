import test from 'node:test';
import assert from 'node:assert/strict';
import {generateKeyPairSync,sign} from 'node:crypto';
import {createRelay} from '../relay/cloudflare-worker.mjs';
import {createHandler} from '../supabase/functions/yay-api/handler.mjs';
import * as S from '../supabase/functions/yay-api/security.mjs';

test('Relay forwards signed login bytes intact and backend rejects a tampered body',async()=>{
 const pair=generateKeyPairSync('ec',{namedCurve:'prime256v1'}),publicKey=S.base64(pair.publicKey.export({type:'spki',format:'der'}));
 const passwordHash=await S.hashPassword('fictional-password-2026'),calls=[];
 const handler=createHandler({rpc:async(action,payload)=>{calls.push(action);if(action==='login_lookup')return {id:'11111111-2222-4333-8444-555555555555',password_hash:passwordHash};if(action==='login_finish')return {ok:true};return {};},vault:new S.Vault(S.base64(new Uint8Array(32).fill(9))),adminHash:passwordHash});
 const relay=createRelay(request=>{assert.equal(request.url,'https://vjcpphrhdzdywmrfndta.supabase.co/functions/v1/yay-api/v1/login');assert.equal(request.redirect,'manual');return handler(request);});
 const body=JSON.stringify({username:'fictional-user',password:'fictional-password-2026',public_key:publicKey,device_name:'Fixture tablet'}),time=String(Math.floor(Date.now()/1000)),nonce=crypto.randomUUID();
 const signature=S.base64(sign('sha256',Buffer.from(['POST','/v1/login',time,nonce,await S.sha(body)].join('\n')),pair.privateKey));
 const headers={'Content-Type':'application/json','X-Yay-Time':time,'X-Yay-Nonce':nonce,'X-Yay-Signature':signature};
 const response=await relay(new Request('https://relay.example/yay-api/v1/login',{method:'POST',headers,body}));
 assert.equal(response.status,200);assert.ok((await response.json()).token);assert.equal(response.headers.get('cache-control'),'no-store');assert.ok(calls.includes('login_finish'));
 const tampered=await relay(new Request('https://relay.example/yay-api/v1/login',{method:'POST',headers,body:body.replace('Fixture tablet','Changed device')}));assert.equal(tampered.status,401);
});
test('Relay preserves bearer/device headers, upstream denial and the fixed destination',async()=>{
 let calls=0;const relay=createRelay(async request=>{calls++;assert.equal(request.headers.get('authorization'),'Bearer fictional-token');assert.equal(request.headers.get('x-yay-signature'),'fictional-signature');assert.equal(request.url,'https://vjcpphrhdzdywmrfndta.supabase.co/functions/v1/yay-api/v1/bootstrap');return new Response('{"error":"denied"}',{status:403,headers:{'content-type':'application/json'}});});
 const response=await relay(new Request('https://relay.example/yay-api/v1/bootstrap',{headers:{Authorization:'Bearer fictional-token','X-Yay-Signature':'fictional-signature'}}));assert.equal(response.status,403);assert.equal(calls,1);
});
test('Relay rejects admin routes, arbitrary upstreams, oversized bodies and unsupported methods before forwarding',async()=>{
 let calls=0;const relay=createRelay(async()=>{calls++;throw Error('Unexpected request');});
 for(const path of ['/yay-api/admin/login','/yay-api/healthz?url=https://evil.example','/https://evil.example','/yay-api/v1/login?target=evil'])assert.equal((await relay(new Request('https://relay.example'+path))).status,404);
 assert.equal((await relay(new Request('https://relay.example/yay-api/v1/login',{method:'POST',body:'x'.repeat(32769)}))).status,413);
 assert.equal((await relay(new Request('https://relay.example/yay-api/healthz',{method:'POST'}))).status,405);assert.equal(calls,0);
});
