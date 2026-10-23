import { requireValue } from './security.mjs';
function decode(value) { return new TextDecoder().decode(Uint8Array.from(atob(value.replaceAll('-','+').replaceAll('_','/').padEnd(Math.ceil(value.length/4)*4,'=')),c=>c.charCodeAt(0))); }
function validUUID(value) { requireValue(/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value),'Server UUID is invalid');return value.toLowerCase(); }
export function importURI(uri) {
  requireValue(typeof uri==='string'&&uri.length<=20000,'Invalid server link');uri=uri.trim();let out,q,name;
  if (/^(hysteria2|hy2):\/\//i.test(uri)) {
    const u=new URL(uri),p=Object.fromEntries(u.searchParams);
    requireValue(Object.keys(p).every(k=>['sni','insecure','obfs','obfs-password'].includes(k)),'Unsupported Hysteria2 link option; use the original sing-box JSON for ECH settings');
    requireValue(['0','false','',undefined].includes(p.insecure),'TLS certificate verification must stay enabled');
    const node={type:'hysteria2',tag:decodeURIComponent(u.hash.slice(1))||'Server',server:u.hostname.replace(/^\[|\]$/g,''),server_port:Number(u.port||443),password:decodeURIComponent(u.username)+(u.password?':'+decodeURIComponent(u.password):''),tls:{enabled:true}};
    if(p.sni)node.tls.server_name=p.sni;
    if(p.obfs||p['obfs-password'])node.obfs={type:p.obfs,password:p['obfs-password']};
    return importOutbound(node);
  }
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

// Accept node objects, never whole routing configs or file paths. Preserve provider TLS
// settings; do not guess an SNI or disable certificate verification to force a connection.
export function importOutbound(value) {
  function keys(obj,allowed,label) {
    requireValue(obj&&typeof obj==='object'&&!Array.isArray(obj),`Invalid ${label}`);
    requireValue(Object.keys(obj).every(k=>allowed.includes(k)),`Unsupported ${label} option`);
  }
  function str(s,label,max=4096) {requireValue(typeof s==='string'&&s.length>0&&s.length<=max,`Invalid ${label}`);}
  function strings(a,label){requireValue(Array.isArray(a)&&a.length>0&&a.length<=32,`Invalid ${label}`);for(const s of a)str(s,label);}
  keys(value,['type','tag','server','server_port','uuid','flow','packet_encoding','password','obfs','tls','transport'],'node');
  requireValue(['vless','hysteria2'].includes(value.type),'JSON imports support VLESS and Hysteria2 nodes');
  const out=JSON.parse(JSON.stringify(value)),name=out.tag||'Server';str(name,'name',80);str(out.server,'server',253);
  requireValue(!/[\s/@?#]/.test(out.server),'Invalid server host');
  const tls=out.tls;
  keys(tls,['enabled','server_name','insecure','alpn','utls','reality','ech'],'TLS');
  requireValue(tls.enabled===true,'TLS must be enabled');
  requireValue(tls.insecure===undefined||tls.insecure===false,'TLS certificate verification must stay enabled');
  if(tls.server_name!==undefined)str(tls.server_name,'TLS server name',253);
  if(tls.alpn!==undefined)strings(tls.alpn,'ALPN');
  if(tls.utls!==undefined){keys(tls.utls,['enabled','fingerprint'],'uTLS');requireValue(typeof tls.utls.enabled==='boolean','Invalid uTLS enabled');str(tls.utls.fingerprint,'fingerprint',64);}
  if(tls.reality!==undefined){keys(tls.reality,['enabled','public_key','short_id'],'REALITY');requireValue(tls.reality.enabled===true,'Invalid REALITY enabled');requireValue(typeof tls.reality.public_key==='string'&&/^[A-Za-z0-9_-]{43}$/.test(tls.reality.public_key),'Invalid REALITY public key');requireValue(tls.reality.short_id===undefined||typeof tls.reality.short_id==='string'&&/^(?:[0-9a-fA-F]{2}){0,8}$/.test(tls.reality.short_id),'Invalid REALITY short ID');}
  if(tls.ech!==undefined){keys(tls.ech,['enabled','config'],'ECH');requireValue(typeof tls.ech.enabled==='boolean','Invalid ECH enabled');if(tls.ech.config!==undefined)strings(tls.ech.config,'ECH config');}
  if(out.type==='vless') {
    requireValue(out.password===undefined&&out.obfs===undefined,'Hysteria2 options on a VLESS node');
    out.uuid=validUUID(out.uuid);
    requireValue(out.flow===undefined||out.flow===''||out.flow==='xtls-rprx-vision','Unsupported VLESS flow');
    requireValue(out.packet_encoding===undefined||['','xudp','packetaddr'].includes(out.packet_encoding),'Unsupported packet encoding');
    if(out.transport!==undefined){const t=out.transport;keys(t,['type','path','headers','service_name'],'transport');
      requireValue(['ws','grpc'].includes(t.type),'Supported JSON transports: WebSocket and gRPC; omit transport for TCP');
      if(t.type==='ws'){requireValue(t.service_name===undefined,'gRPC option on WebSocket');if(t.path!==undefined)str(t.path,'WebSocket path');if(t.headers!==undefined){keys(t.headers,['Host'],'WebSocket headers');str(t.headers.Host,'WebSocket host',253);}}
      else {requireValue(t.path===undefined&&t.headers===undefined,'WebSocket option on gRPC');if(t.service_name!==undefined)str(t.service_name,'gRPC service');}
    }
  } else {
    requireValue(['uuid','flow','packet_encoding','transport'].every(k=>out[k]===undefined),'VLESS options on a Hysteria2 node');
    requireValue(tls.reality===undefined&&tls.utls===undefined,'REALITY/uTLS are not supported for Hysteria2 QUIC');
    str(out.password,'Hysteria2 password');
    if(out.obfs!==undefined){keys(out.obfs,['type','password'],'obfuscation');requireValue(out.obfs.type==='salamander','Unsupported Hysteria2 obfuscation');str(out.obfs.password,'obfuscation password');}
  }
  return {name,outbound:validateOutbound(out)};
}
export function configFor(out) {
 return {log:{disabled:true},dns:{servers:[{type:'udp',tag:'bootstrap',server:'1.1.1.1'},{type:'https',tag:'remote',server:'1.1.1.1',server_port:443,path:'/dns-query',tls:{enabled:true,server_name:'cloudflare-dns.com'},detour:'proxy'}],final:'remote',strategy:'ipv4_only'},
 inbounds:[{type:'tun',tag:'tun-in',address:['172.19.0.1/30','fdfe:dcba:9876::1/126'],mtu:1400,auto_route:true,stack:'gvisor'}],outbounds:[out],
 route:{rules:[{action:'sniff'},{protocol:'dns',action:'hijack-dns'},{port:53,action:'hijack-dns'}],final:'proxy',auto_detect_interface:true,default_domain_resolver:'bootstrap'}};
}

