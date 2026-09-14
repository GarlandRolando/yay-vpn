import test from 'node:test';
import assert from 'node:assert/strict';
import { generateKeyPairSync,sign } from 'node:crypto';
import { createHandler } from '../supabase/functions/yay-api/handler.mjs';
import * as S from '../supabase/functions/yay-api/security.mjs';

const origin='https://abcdefghijklmnopqrst.supabase.co/functions/v1/yay-api';
const userID='11111111-2222-4333-8444-555555555555';
const oldDeviceID='aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee';
const newDeviceID='99999999-8888-4777-8666-555555555555';
const vault=new S.Vault(S.base64(new Uint8Array(32).fill(7)));
const adminHash=await S.hashPassword('Administrator-password-2026');

async function signedLogin(handler,pair,body){
  const path='/v1/login',raw=S.bytes(JSON.stringify(body));
  const ts=String(Math.floor(Date.now()/1000)),nonce=crypto.randomUUID();
  const message=['POST',path,ts,nonce,await S.sha(raw)].join('\n');
  const signature=S.base64(sign('sha256',Buffer.from(message),pair.privateKey));
  return handler(new Request(origin+path,{method:'POST',headers:{'content-type':'application/json','x-yay-time':ts,'x-yay-nonce':nonce,'x-yay-signature':signature},body:JSON.stringify(body)}));
}

test('Full device login offers a chooser and replacement login revokes the selected slot',async()=>{
  const pair=generateKeyPairSync('ec',{namedCurve:'prime256v1'});
  const publicKey=S.base64(pair.publicKey.export({type:'spki',format:'der'}));
  const userHash=await S.hashPassword('User-password-2026');
  let replacementPayload;
  const rpc=async(action,payload)=>{
    if(action==='rate')return {ok:true};
    if(action==='login_lookup')return {id:userID,password_hash:userHash};
    if(action==='login_finish')return {status:409,error:'Device limit reached. Ask your administrator to remove an old device.'};
    if(action==='login_replace'){
      if(payload.replace_device_id){replacementPayload=payload;return {account:{device_limit:2,devices_used:2},device_id:newDeviceID};}
      return {requires_device_replacement:true,device_limit:2,devices:[{id:oldDeviceID,name:'Old phone',created_at:1,last_seen:2}]};
    }
    throw new Error('Unexpected action '+action);
  };
  const handler=createHandler({rpc,vault,adminHash});
  const base={username:'garland',password:'User-password-2026',public_key:publicKey,device_name:'New phone'};

  const full=await signedLogin(handler,pair,base);
  assert.equal(full.status,200);const chooser=await full.json();
  assert.equal(chooser.requires_device_replacement,true);assert.equal(chooser.devices.length,1);assert.equal(chooser.devices[0].id,oldDeviceID);assert.equal(chooser.token,undefined);

  const replaced=await signedLogin(handler,pair,{...base,replace_device_id:oldDeviceID});
  assert.equal(replaced.status,200);const session=await replaced.json();
  assert.equal(session.device_id,newDeviceID);assert.ok(typeof session.token==='string'&&session.token.length>=32);
  assert.equal(replacementPayload.replace_device_id,oldDeviceID);assert.equal(replacementPayload.public_key,publicKey);assert.equal(replacementPayload.verified_hash,userHash);
  assert.ok(!Object.hasOwn(replacementPayload,'password'));
});
