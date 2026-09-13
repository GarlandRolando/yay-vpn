import { requireValue } from './security.mjs';
function decode(value) { return new TextDecoder().decode(Uint8Array.from(atob(value.replaceAll('-','+').replaceAll('_','/').padEnd(Math.ceil(value.length/4)*4,'=')),c=>c.charCodeAt(0))); }
function validUUID(value) { requireValue(/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value),'Server UUID is invalid');return value.toLowerCase(); }
export function importURI(uri) {
  requireValue(typeof uri==='string'&&uri.length<=20000,'Invalid server link');uri=uri.trim();let out,q,name;
  if(uri.startsWith('vmess://')) {
    const v=JSON.parse(decode(uri.slice(8)));requireValue(['none','',undefined].includes(v.type),'Unsupported VMess header');
    out={type:'vmess',server:v.add,server_port:Number(v.port),uuid:validUUID(v.id),security:v.scy||'auto',alter_id:Number(v.aid||0)};
    requireValue(['auto','aes-128-gcm','chacha20-poly1305','none','zero'].includes(out.security),'Unsupported VMess encryption');
    q={type:v.net||'tcp',security:v.tls||'none',path:v.path||'/',host:v.host||'',sni:v.sni||v.host||v.add};name=v.ps||'Server';
    requireValue(!['none','zero'].includes(out.security)||q.security==='tls','Unencrypted VMess requires TLS');
  } else {
    const u=new URL(uri);q=Object.fromEntries(u.searchParams);name=decodeURIComponent(u.hash.slice(1))||'Server';
    const protocol=u.protocol.slice(0,-1);requireValue(['vless','trojan','ss'].includes(protocol),'Paste a vless://, vmess://, trojan:// or ss:// node link, not a subscription URL.');
    out={type:protocol==='ss'?'shadowsocks':protocol,server:u.hostname.replace(/^\[|\]$/g,''),server_port:Number(u.port)};
    requireValue(Boolean(u.username),'Server credential is missing');
    if(protocol==='ss') {
      requireValue(Object.keys(q).length===0,'Shadowsocks plugins are not supported');
      let credentials=decodeURIComponent(u.username)+(u.password?':'+decodeURIComponent(u.password):'');if(!credentials.includes(':'))credentials=decode(credentials);
      const split=credentials.indexOf(':');requireValue(split>0,'Invalid Shadowsocks credential');out.method=credentials.slice(0,split);out.password=credentials.slice(split+1);
      requireValue(['aes-128-gcm','aes-256-gcm','chacha20-ietf-poly1305','2022-blake3-aes-128-gcm','2022-blake3-aes-256-gcm','2022-blake3-chacha20-poly1305'].includes(out.method),'Unsupported Shadowsocks method');
      return {outbound:validateOutbound(out),name};
    }
    const allowed=new Set(['type','security','sni','peer','fp','pbk','sid','flow','path','host','serviceName','encryption','alpn','allowInsecure','insecure','spx']);
    requireValue(Object.keys(q).every(k=>allowed.has(k)),'This link contains unsupported options');
    requireValue(!q.encryption||q.encryption==='none','Unsupported VLESS encryption');
    if(protocol==='vless') {out.uuid=validUUID(decodeURIComponent(u.username));if(q.flow){requireValue(q.flow==='xtls-rprx-vision','Unsupported VLESS flow');out.flow=q.flow;}}
    else {out.password=decodeURIComponent(u.username);q.security??='tls';}
  }
  requireValue(['0','false','',undefined].includes(q.allowInsecure)&&['0','false','',undefined].includes(q.insecure),'TLS certificate verification must stay enabled');
  if(['tls','reality'].includes(q.security)) {
    out.tls={enabled:true,server_name:q.sni||q.peer||out.server};
    if(q.alpn)out.tls.alpn=q.alpn.split(',');if(q.fp)out.tls.utls={enabled:true,fingerprint:q.fp};
    if(q.security==='reality'){requireValue(Boolean(q.pbk),'REALITY public key is missing');out.tls.reality={enabled:true,public_key:q.pbk,short_id:q.sid||''};out.tls.utls??={enabled:true,fingerprint:'chrome'};}
  } else {requireValue(!q.security||q.security==='none','Unsupported security mode');requireValue(out.type!=='vless','VLESS requires TLS or REALITY');}
  const transport=q.type||'tcp';
  if(transport==='ws'){out.transport={type:'ws',path:q.path||'/'};if(q.host)out.transport.headers={Host:q.host};}
  else if(transport==='grpc')out.transport={type:'grpc',service_name:q.serviceName||q.path||''};
  else requireValue(['tcp','raw'].includes(transport),'Supported transports: TCP, WebSocket and gRPC');
  return {outbound:validateOutbound(out),name};
}
function validateOutbound(out) {requireValue(typeof out.server==='string'&&out.server.trim().length>0,'Server host is missing');requireValue(Number.isInteger(out.server_port)&&out.server_port>0&&out.server_port<=65535,'Server port is invalid');out.tag='proxy';return out;}
export function configFor(out) {
 return {log:{disabled:true},dns:{servers:[{type:'udp',tag:'bootstrap',server:'1.1.1.1'},{type:'https',tag:'remote',server:'1.1.1.1',server_port:443,path:'/dns-query',tls:{enabled:true,server_name:'cloudflare-dns.com'},detour:'proxy'}],final:'remote',strategy:'ipv4_only'},
 inbounds:[{type:'tun',tag:'tun-in',address:['172.19.0.1/30','fdfe:dcba:9876::1/126'],mtu:1400,auto_route:true,stack:'gvisor'}],outbounds:[out],
 route:{rules:[{action:'sniff'},{protocol:'dns',action:'hijack-dns'},{port:53,action:'hijack-dns'}],final:'proxy',auto_detect_interface:true,default_domain_resolver:'bootstrap'}};
}
