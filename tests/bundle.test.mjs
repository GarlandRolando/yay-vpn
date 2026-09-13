import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { stripTypeScriptTypes } from 'node:module';
import vm from 'node:vm';
test('Dashboard single-file bundle starts and serves health without external calls',async()=>{
 const source=await readFile(new URL('../supabase/PASTE-INTO-EDGE.ts',import.meta.url),'utf8');
 const code=stripTypeScriptTypes(source,{mode:'strip'});let handler;
 const env={SUPABASE_URL:'https://abcdefghijklmnopqrst.supabase.co',SUPABASE_SERVICE_ROLE_KEY:'test-only-key',YAY_MASTER_KEY:btoa(String.fromCharCode(...new Uint8Array(32).fill(9))),YAY_ADMIN_PASSWORD_HASH:'pbkdf2$600000$test$test'};
 const context={crypto,TextEncoder,TextDecoder,Uint8Array,Request,Response,URL,AbortSignal,btoa,atob,console,fetch:()=>{throw Error('Unexpected network call');},Deno:{env:{get:n=>env[n]},serve:h=>{handler=h;}}};
 new vm.Script(code).runInNewContext(context);
 const response=await handler(new Request(env.SUPABASE_URL+'/functions/v1/yay-api/healthz'));
 assert.equal(response.status,200);assert.equal((await response.json()).service,'Yay VPN Supabase');
});
