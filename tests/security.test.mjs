import test from 'node:test';
import assert from 'node:assert/strict';
import { generateKeyPairSync, sign } from 'node:crypto';
import * as S from '../supabase/functions/yay-api/security.mjs';
import { importURI, configFor } from '../supabase/functions/yay-api/profiles.mjs';

test('PBKDF2 hashes verify, are salted, and reject wrong passwords',async()=>{const a=await S.hashPassword('Correct-password-2026'),b=await S.hashPassword('Correct-password-2026');assert.notEqual(a,b);assert.equal(await S.verifyPassword('Correct-password-2026',a),true);assert.equal(await S.verifyPassword('wrong-password',a),false);assert.equal(await S.verifyPassword('password','garbage'),false);});
test('AES-GCM encrypts, detects tampering, and derives a separate stable seed key',async()=>{const v=new S.Vault(S.base64(crypto.getRandomValues(new Uint8Array(32))));const a=await v.seal({secret:'never plaintext'}),b=await v.seal({secret:'never plaintext'});assert.notEqual(a,b);assert.deepEqual(await v.open(a),{secret:'never plaintext'});const x=S.unbase64(a);x[x.length-1]^=1;await assert.rejects(v.open(S.base64(x)));assert.equal(await v.seedKey(),await v.seedKey());assert.notEqual(await v.seedKey(),S.base64(v.raw));});
test('Android-format DER signatures verify with WebCrypto; altered body and wrong key fail',async()=>{
 const {privateKey,publicKey}=generateKeyPairSync('ec',{namedCurve:'prime256v1'});const key=S.base64(publicKey.export({type:'spki',format:'der'}));
 const path='/v1/connect',raw=S.bytes('{"server_id":"example"}'),ts=String(Math.floor(Date.now()/1000)),nonce=crypto.randomUUID();
 const message=['POST',path,ts,nonce,await S.sha(raw)].join('\n');const sig=sign('sha256',Buffer.from(message),privateKey);
 const request=new Request('https://example.supabase.co/functions/v1/yay-api'+path,{method:'POST',headers:{'x-yay-time':ts,'x-yay-nonce':nonce,'x-yay-signature':S.base64(sig)}});
 assert.equal(typeof await S.verifyDevice(request,path,raw,key),'string');
 await assert.rejects(S.verifyDevice(request,path,S.bytes('{}'),key));
 await assert.rejects(S.verifyDevice(request,'/v1/logout',raw,key));
 const other=generateKeyPairSync('ec',{namedCurve:'prime256v1'}).publicKey.export({type:'spki',format:'der'});await assert.rejects(S.verifyDevice(request,path,raw,S.base64(other)));
});
test('Expired signature timestamp and malformed DER are rejected',async()=>{assert.throws(()=>S.derToRaw(new Uint8Array([48,1,2])));await assert.rejects(S.verifyDevice(new Request('https://example.com',{headers:{'x-yay-time':'1000000000','x-yay-nonce':crypto.randomUUID()}}),'/v1/bootstrap',new Uint8Array(),'badkey'));});
test('Imports supported node schemes and generates current engine config',()=>{
 const links=['vless://11111111-2222-4333-8444-555555555555@example.com:443?security=tls&type=ws&path=%2Fvpn#SG','trojan://password@example.com:443?sni=example.com','ss://YWVzLTI1Ni1nY206cGFzc3dvcmQ@example.com:8388#JP'];
 const vm={add:'example.com',port:443,id:'11111111-2222-4333-8444-555555555555',tls:'tls',net:'grpc',path:'service'};links.push('vmess://'+S.base64(S.bytes(JSON.stringify(vm))));
 for(const uri of links){const {outbound}=importURI(uri);assert.equal(outbound.tag,'proxy');assert.equal(configFor(outbound).route.final,'proxy');}
});
test('Rejects unsupported protocols, insecure TLS and invalid values',()=>{
 for(const uri of ['https://subscription.example/list','vless://11111111-2222-4333-8444-555555555555@example.com:443?security=none','trojan://secret@example.com:443?insecure=1','trojan://secret@example.com:443?type=kcp','trojan://secret@example.com:443?unsupported=x','vless://bad-id@example.com:443?security=tls'])assert.throws(()=>importURI(uri));
});
