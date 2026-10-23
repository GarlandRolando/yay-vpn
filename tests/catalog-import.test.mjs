import test from 'node:test';
import assert from 'node:assert/strict';
import { importURI,importOutbound } from '../supabase/functions/yay-api/profiles.mjs';
import { prepareCatalog,replaceCatalog } from '../scripts/catalog-import.mjs';

const hy=()=>({type:'hysteria2',tag:'ASIA-Singapore-01-HY',server:'vpn.example.com',server_port:443,password:'test-only-password',obfs:{type:'salamander',password:'test-obfs'},tls:{enabled:true,server_name:'cert.example.com',ech:{enabled:true,config:['test-config']}}});
const vl=()=>({type:'vless',tag:'EU-Switzerland-01',server:'vpn.example.com',server_port:443,uuid:'11111111-2222-4333-8444-555555555555',flow:'xtls-rprx-vision',packet_encoding:'xudp',tls:{enabled:true,server_name:'cert.example.com',utls:{enabled:true,fingerprint:'firefox'},reality:{enabled:true,public_key:'a'.repeat(43),short_id:'0011223344556677'}}});
test('Node import preserves TLS, ECH, obfuscation and packet encoding without mutating input',()=>{
  for(const node of [hy(),vl()]){const original=structuredClone(node),result=importOutbound(node);assert.deepEqual(node,original);assert.deepEqual(result.outbound,{...original,tag:'proxy'});}
  const node=hy();node.tls={enabled:true};assert.deepEqual(importOutbound(node).outbound.tls,{enabled:true});
});
test('Hy2 aliases, omitted port and percent-encoded password import correctly',()=>{
  for(const scheme of ['hy2','hysteria2']){const out=importURI(scheme+'://user%3Apass%40word@vpn.example.com/?sni=cert.example.com&obfs=salamander&obfs-password=a%2Bb#Singapore').outbound;assert.equal(out.password,'user:pass@word');assert.equal(out.obfs.password,'a+b');assert.equal(out.server_port,443);assert.equal(out.type,'hysteria2');}
  assert.throws(()=>importURI('hy2://pass@vpn.example.com/?insecure=1'));
  assert.throws(()=>importURI('hy2://pass@vpn.example.com/?pinSHA256=unknown'));
});
test('Node JSON cannot inject routes, local file paths, detours, insecure TLS or mismatched protocol fields',()=>{
  for(const change of [{detour:'direct'},{tls:{enabled:true,certificate_path:'/private/key'}},{tls:{enabled:true,insecure:true}},{tls:{enabled:true,ech:{enabled:true,config_path:'/private/key'}}},{type:'direct'},{server_port:0},{obfs:{type:'unsupported',password:'test'}},{uuid:vl().uuid},{transport:{type:'ws'}}])assert.throws(()=>importOutbound({...hy(),...change}));
  assert.throws(()=>importOutbound({...vl(),password:'wrong-protocol'}));
  assert.throws(()=>importOutbound({...vl(),tls:{enabled:true,reality:{enabled:true,public_key:'bad',short_id:'odd'}}}));
});
test('Catalog preflight rejects empty, malformed, duplicate and unknown-country inputs',()=>{
  for(const document of [{outbounds:[]},{outbounds:[hy(),hy()]},{outbounds:[{...hy(),tag:'Unknown region'}]},{outbounds:[hy(),{...vl(),uuid:'bad'}]}])assert.throws(()=>prepareCatalog(document));
  const result=prepareCatalog({outbounds:[hy(),vl()]});assert.deepEqual(result.entries.map(x=>x.location),['Singapore','Switzerland']);
});
function mockCloud({failSave=0,concurrent=false}={}) {
  const rows=[{id:'old',name:'Old Japan',location:'Japan',enabled:1,protocol:'vless'}];const calls=[];let saves=0;
  const api=async(path,method='GET',body)=>{calls.push([method,path,body]);if(method==='GET')return {servers:structuredClone(rows)};
    if(body.outbound&&++saves===failSave)throw Error('Upload failed');
    if(method==='POST'){rows.push({id:'new-'+rows.length,name:body.name,location:body.location,protocol:body.outbound.type,enabled:1});if(concurrent)rows.push({id:'concurrent',name:'Other admin addition',enabled:1});}
    else {const row=rows.find(r=>r.id===path.split('/').at(-1));Object.assign(row,body);if(body.outbound)row.protocol=body.outbound.type;}
    return {ok:true};
  };return {api,rows,calls};
}
test('Failed upload preserves old active catalog; rerun finishes without duplicate creation',async()=>{
  const cloud=mockCloud({failSave:2}),{entries}=prepareCatalog({outbounds:[hy(),vl()]});
  await assert.rejects(replaceCatalog(entries,cloud.api));assert.equal(cloud.rows[0].enabled,1);assert.equal(cloud.calls.some(c=>c[2]?.enabled===0),false);
  const result=await replaceCatalog(entries,cloud.api);assert.equal(result.active,2);assert.equal(cloud.rows.length,3);assert.equal(cloud.rows[0].enabled,0);
  const again=await replaceCatalog(entries,cloud.api);assert.equal(again.added,0);assert.equal(again.updated,2);
});
test('Concurrent admin addition is reported and is never retired by this import',async()=>{
  const cloud=mockCloud({concurrent:true});await assert.rejects(replaceCatalog(prepareCatalog([hy()]).entries,cloud.api),/changed during import/);
  assert.equal(cloud.rows.find(r=>r.id==='concurrent').enabled,1);
});
