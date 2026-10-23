import { importOutbound } from '../supabase/functions/yay-api/profiles.mjs';

const countries=['Singapore','Hong Kong','Japan','India','South Africa','South Korea','United States','United Kingdom','Australia','Germany','Spain','Brazil','Poland','France','Belgium','Switzerland'];
const normalize=s=>s.toLowerCase().replace(/[^a-z]/g,'');
export function prepareCatalog(document) {
  const nodes=Array.isArray(document)?document:document?.outbounds;
  if(!Array.isArray(nodes)||nodes.length===0||nodes.length>1000)throw Error('Expected 1–1000 sing-box nodes in an outbounds array.');
  const names=new Set(),warnings=[];
  const entries=nodes.map((node,index)=>{
    let imported;try{imported=importOutbound(node);}catch(e){throw Error(`Node ${index+1}: ${e.message}`);}
    const name=imported.name.trim();if(!name||names.has(name))throw Error(`Node ${index+1}: missing or duplicate name.`);names.add(name);
    const location=countries.find(c=>normalize(name).includes(normalize(c)));
    if(!location)throw Error(`Node ${index+1}: country not recognized in tag. Add its country to scripts/catalog-import.mjs.`);
    if(node.type==='hysteria2'&&!node.tls.server_name)warnings.push(`Node ${index+1}: no TLS server name supplied; certificate must validate for the server address.`);
    if(node.tls.ech?.enabled&&!node.tls.ech.config)warnings.push(`Node ${index+1}: ECH enabled without an explicit config; provider/DNS configuration must support discovery.`);
    return {name,location,outbound:imported.outbound,enabled:1};
  });
  return {entries,warnings};
}

// Add/update every replacement first. Retire old options only after every write
// succeeded and the target catalog is visible. Interrupted runs can be rerun.
export async function replaceCatalog(entries,api,progress=()=>{}) {
  const before=(await api('/admin/servers')).servers;
  const targets=new Map();
  for(const entry of entries){
    const matches=before.filter(s=>s.name===entry.name);
    if(matches.length>1)throw Error('Duplicate existing server name; resolve duplicates in the admin panel before replacing.');
    targets.set(entry.name,matches[0]);
  }
  let added=0,updated=0,paused=0;
  for(const entry of entries){
    const old=targets.get(entry.name);
    await api('/admin/servers'+(old?'/'+encodeURIComponent(old.id):''),old?'PATCH':'POST',entry);
    if(old)updated++;else added++;
    progress(`Saved ${added+updated}/${entries.length}`);
  }
  const saved=(await api('/admin/servers')).servers,keep=new Set();
  for(const entry of entries){
    const matches=saved.filter(s=>s.name===entry.name);
    if(matches.length!==1||matches[0].enabled!==1||matches[0].protocol!==entry.outbound.type||matches[0].location!==entry.location)throw Error('Replacement verification failed; old unrelated servers have not been paused.');
    keep.add(matches[0].id);
  }
  // Only retire records in the starting snapshot. Concurrent admin additions are
  // never silently removed; the final exact-catalog check reports them instead.
  for(const old of before)if(!keep.has(old.id)&&old.enabled===1){await api('/admin/servers/'+encodeURIComponent(old.id),'PATCH',{enabled:0});paused++;}
  const active=(await api('/admin/servers')).servers.filter(s=>s.enabled===1);
  if(active.length!==entries.length||active.some(s=>!keep.has(s.id)))throw Error('Catalog changed during import. Review the admin panel and rerun to reconcile.');
  return {added,updated,paused,active:active.length};
}
