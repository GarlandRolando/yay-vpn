import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
const config=JSON.parse(await readFile(new URL('../relay/vercel.json',import.meta.url),'utf8'));
test('Relay destinations stay fixed to the existing backend and exclude the admin API',()=>{
 assert.deepEqual(config.rewrites.map(r=>r.source),['/yay-api/healthz','/yay-api/v1/:path*']);
 for(const rule of config.rewrites){const destination=new URL(rule.destination);assert.equal(destination.origin,'https://vjcpphrhdzdywmrfndta.supabase.co');assert.equal(destination.search,'');assert.equal(destination.username,'');assert.equal(destination.password,'');assert.ok(destination.pathname.startsWith('/functions/v1/yay-api/'));}
 assert.equal(config.rewrites[0].destination,'https://vjcpphrhdzdywmrfndta.supabase.co/functions/v1/yay-api/healthz');
 assert.equal(config.rewrites[1].destination,'https://vjcpphrhdzdywmrfndta.supabase.co/functions/v1/yay-api/v1/:path*');
});
test('Account responses cannot be cached by the relay CDN',()=>{
 const headers=Object.fromEntries(config.headers.find(r=>r.source==='/yay-api/:path*').headers.map(h=>[h.key.toLowerCase(),h.value]));
 for(const name of ['cache-control','cdn-cache-control','vercel-cdn-cache-control'])assert.equal(headers[name],'no-store');
 assert.equal(headers['x-vercel-enable-rewrite-caching'],'0');
});
