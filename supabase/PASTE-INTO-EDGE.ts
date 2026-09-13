// Yay VPN — Supabase Dashboard single-file alternative. Generated; do not edit modules here.
const S=(()=>{
const encoder = new TextEncoder();
class ApiError extends Error { constructor(status, message) { super(message); this.status = status; } }
function requireValue(value, message, status = 400) { if (!value) throw new ApiError(status, message); }
const bytes = value => encoder.encode(value);
function base64(value) { let s=''; for (const b of value) s+=String.fromCharCode(b); return btoa(s); }
function unbase64(value) { return Uint8Array.from(atob(value), c=>c.charCodeAt(0)); }
const randomToken = () => base64(crypto.getRandomValues(new Uint8Array(32))).replaceAll('+','-').replaceAll('/','_').replaceAll('=','');
async function sha(value) { const raw=typeof value==='string'?bytes(value):value; return Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',raw)),b=>b.toString(16).padStart(2,'0')).join(''); }
function constantEqual(a,b) { if(a.length!==b.length)return false; let n=0; for(let i=0;i<a.length;i++)n|=a[i]^b[i]; return n===0; }
async function hashPassword(password, salt=crypto.getRandomValues(new Uint8Array(16))) {
  const key=await crypto.subtle.importKey('raw',bytes(password),'PBKDF2',false,['deriveBits']);
  const hash=await crypto.subtle.deriveBits({name:'PBKDF2',hash:'SHA-256',salt,iterations:600000},key,256);
  return `pbkdf2$600000$${base64(salt)}$${base64(new Uint8Array(hash))}`;
}
async function verifyPassword(password, stored) {
  try { const [type,iterations,salt,expected]=stored.split('$');
    if(type!=='pbkdf2'||iterations!=='600000')return false;
    const actual=(await hashPassword(password,unbase64(salt))).split('$')[3];
    return constantEqual(unbase64(actual),unbase64(expected));
  } catch { return false; }
}
class Vault {
  constructor(master) { const raw=unbase64(master); requireValue(raw.length===32,'Invalid backend encryption key',503); this.raw=raw; }
  async aes() { return crypto.subtle.importKey('raw',this.raw,'AES-GCM',false,['encrypt','decrypt']); }
  async seal(value) { const iv=crypto.getRandomValues(new Uint8Array(12)); const encrypted=new Uint8Array(await crypto.subtle.encrypt({name:'AES-GCM',iv,additionalData:bytes('yay-vpn-v1')},await this.aes(),bytes(JSON.stringify(value)))); const result=new Uint8Array(12+encrypted.length);result.set(iv);result.set(encrypted,12);return base64(result); }
  async open(value) { const raw=unbase64(value);const decrypted=await crypto.subtle.decrypt({name:'AES-GCM',iv:raw.slice(0,12),additionalData:bytes('yay-vpn-v1')},await this.aes(),raw.slice(12));return JSON.parse(new TextDecoder().decode(decrypted)); }
  async seedKey() { const key=await crypto.subtle.importKey('raw',this.raw,'HKDF',false,['deriveBits']);return base64(new Uint8Array(await crypto.subtle.deriveBits({name:'HKDF',hash:'SHA-256',salt:bytes('yay-vpn-supabase-v1'),info:bytes('encrypted-preload')},key,256))); }
}
// Android SHA256withECDSA produces ASN.1 DER; WebCrypto expects 32-byte r || 32-byte s.
function derToRaw(der) {
  let p=0;
  requireValue(der[p++]===0x30,'Invalid device signature',401);
  const len=der[p++];requireValue(len<128&&len===der.length-2,'Invalid device signature',401);
  const raw=new Uint8Array(64);
  for(let part=0;part<2;part++) {
    requireValue(der[p++]===0x02,'Invalid device signature',401);const n=der[p++];
    requireValue(n>0&&n<=33&&p+n<=der.length,'Invalid device signature',401);
    let v=der.slice(p,p+n);p+=n;
    requireValue(!(v[0]&0x80),'Invalid device signature',401);
    if(v.length>1&&v[0]===0){requireValue(Boolean(v[1]&0x80),'Invalid device signature',401);v=v.slice(1);}
    requireValue(v.length<=32,'Invalid device signature',401);raw.set(v,part*32+32-v.length);
  }
  requireValue(p===der.length,'Invalid device signature',401);return raw;
}
async function verifyDevice(request,path,raw,publicKey) {
  const timestamp=request.headers.get('x-yay-time')||'',nonce=request.headers.get('x-yay-nonce')||'';
  requireValue(/^\d{10}$/.test(timestamp)&&Math.abs(Date.now()/1000-Number(timestamp))<=90,'Enable automatic date and time on your phone.',401);
  requireValue(/^[a-zA-Z0-9_-]{16,80}$/.test(nonce),'Invalid request nonce',401);
  try {
    const key=await crypto.subtle.importKey('spki',unbase64(publicKey),{name:'ECDSA',namedCurve:'P-256'},false,['verify']);
    const message=[request.method,path,timestamp,nonce,await sha(raw)].join('\n');
    const valid=await crypto.subtle.verify({name:'ECDSA',hash:'SHA-256'},key,derToRaw(unbase64(request.headers.get('x-yay-signature')||'')),bytes(message));
    requireValue(valid,'Device verification failed. Sign in again.',401);
  } catch { throw new ApiError(401,'Device verification failed. Sign in again.'); }
  return sha(publicKey+nonce);
}

return {ApiError,requireValue,bytes,base64,unbase64,randomToken,sha,constantEqual,hashPassword,verifyPassword,Vault,derToRaw,verifyDevice};
})();
const requireValue=S.requireValue;
const P=(()=>{
function decode(value) { return new TextDecoder().decode(Uint8Array.from(atob(value.replaceAll('-','+').replaceAll('_','/').padEnd(Math.ceil(value.length/4)*4,'=')),c=>c.charCodeAt(0))); }
function validUUID(value) { requireValue(/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value),'Server UUID is invalid');return value.toLowerCase(); }
function importURI(uri) {
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
function configFor(out) {
 return {log:{disabled:true},dns:{servers:[{type:'udp',tag:'bootstrap',server:'1.1.1.1'},{type:'https',tag:'remote',server:'1.1.1.1',server_port:443,path:'/dns-query',tls:{enabled:true,server_name:'cloudflare-dns.com'},detour:'proxy'}],final:'remote',strategy:'ipv4_only'},
 inbounds:[{type:'tun',tag:'tun-in',address:['172.19.0.1/30','fdfe:dcba:9876::1/126'],mtu:1400,auto_route:true,stack:'gvisor'}],outbounds:[out],
 route:{rules:[{action:'sniff'},{protocol:'dns',action:'hijack-dns'},{port:53,action:'hijack-dns'}],final:'proxy',auto_detect_interface:true,default_domain_resolver:'bootstrap'}};
}

return {importURI,configFor};
})();
const H=(()=>{
const UUID=/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const dummyHash='pbkdf2$600000$AAAAAAAAAAAAAAAAAAAAAA==$AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=';
function int(value,lo,hi,label) {S.requireValue(Number.isInteger(value)&&value>=lo&&value<=hi,`${label} must be ${lo}–${hi}`);return value;}
function string(value,max,label) {S.requireValue(typeof value==='string'&&value.length<=max,`Invalid ${label}`);return value;}
function uuid(value) {S.requireValue(typeof value==='string'&&UUID.test(value),'Invalid record ID');return value;}
async function readBody(request) {
  if(!request.body)return new Uint8Array();
  const reader=request.body.getReader(),chunks=[];let length=0;
  try {while(true){const {value,done}=await reader.read();if(done)break;length+=value.length;if(length>32768){await reader.cancel();throw new S.ApiError(413,'Request too large');}chunks.push(value);}}
  finally{reader.releaseLock();}
  const body=new Uint8Array(length);let offset=0;for(const c of chunks){body.set(c,offset);offset+=c.length;}return body;
}
function createHandler({rpc,vault,adminHash,allowedOrigins=['http://127.0.0.1:8788','http://localhost:8788']}) {
  async function call(action,payload={}) {const result=await rpc(action,payload);if(result?.error)throw new S.ApiError(result.status||400,result.error);return result;}
  return async function handler(request) {
    const origin=request.headers.get('origin');const headers={'Content-Type':'application/json; charset=utf-8','Cache-Control':'no-store','X-Content-Type-Options':'nosniff','Vary':'Origin'};
    if(origin&&allowedOrigins.includes(origin)){headers['Access-Control-Allow-Origin']=origin;headers['Access-Control-Allow-Headers']='authorization,content-type,x-yay-time,x-yay-nonce,x-yay-signature';headers['Access-Control-Allow-Methods']='GET,POST,PATCH,DELETE,OPTIONS';headers['Access-Control-Max-Age']='600';}
    const respond=(value,status=200)=>new Response(JSON.stringify(value),{status,headers});
    try {
      S.requireValue(!origin||allowedOrigins.includes(origin),'Origin not allowed',403);
      if(request.method==='OPTIONS')return new Response(null,{status:204,headers});
      const url=new URL(request.url);const match=url.pathname.match(/^(?:\/functions\/v1)?\/yay-api(\/.*)?$/);
      S.requireValue(match&&!url.search,'Endpoint not found',404);const path=match[1]||'/';const method=request.method;
      if(method==='GET'&&(path==='/'||path==='/healthz'))return respond({ok:true,service:'Yay VPN Supabase',version:1});
      const raw=await readBody(request);if(raw.length)S.requireValue((request.headers.get('content-type')||'').split(';')[0]==='application/json','Use application/json',415);
      let data;try{data=raw.length?JSON.parse(new TextDecoder('utf-8',{fatal:true}).decode(raw)):{};}catch{throw new S.ApiError(400,'Invalid JSON');}
      S.requireValue(data&&typeof data==='object'&&!Array.isArray(data),'Expected a JSON object');
      const token=(request.headers.get('authorization')||'').replace(/^Bearer /,'');const tokenHash=await S.sha(token);
      if(method==='POST'&&(path==='/v1/login'||path==='/admin/login')) {
        const password=string(data.password,256,'password');const name=path==='/admin/login'?'__admin__':string(data.username,64,'username').trim();
        await call('rate',{name:await S.sha(name.toLowerCase())});
        if(path==='/admin/login') {
          S.requireValue(await S.verifyPassword(password,adminHash),'Incorrect admin password',401);
          const resultToken=S.randomToken();await call('admin_login_create',{token_hash:await S.sha(resultToken)});return respond({token:resultToken});
        }
        const user=await call('login_lookup',{username:name});const valid=await S.verifyPassword(password,user.password_hash||dummyHash);
        S.requireValue(user.id&&valid,'Incorrect username or password',401);
        const publicKey=string(data.public_key,1024,'device key');const nonceHash=await S.verifyDevice(request,path,raw,publicKey);
        const resultToken=S.randomToken();const result=await call('login_finish',{user_id:user.id,verified_hash:user.password_hash,public_key:publicKey,nonce_hash:nonceHash,
          device_name:string(data.device_name||'Android device',80,'device name'),token_hash:await S.sha(resultToken)});
        return respond({...result,token:resultToken});
      }
      if(path.startsWith('/v1/')) {
        S.requireValue(token.length>=32&&token.length<=128,'Sign in to continue',401);
        const context=await call('user_context',{token_hash:tokenHash});const nonceHash=await S.verifyDevice(request,path,raw,context.public_key);
        const auth={token_hash:tokenHash,verified_public_key:context.public_key,nonce_hash:nonceHash};
        if(method==='GET'&&path==='/v1/bootstrap') {const result=await call('user_bootstrap',auth);return respond({...result,seed_key:await vault.seedKey()});}
        if(method==='POST'&&path==='/v1/logout')return respond(await call('user_logout',auth));
        if(method==='POST'&&(path==='/v1/connect'||path==='/v1/heartbeat')) {
          const payload={...auth,server_id:uuid(data.server_id)};
          if(path==='/v1/heartbeat')payload.revision=int(data.revision,1,2147483647,'Revision');
          const result=await call(path==='/v1/connect'?'user_connect':'user_heartbeat',payload);
          if(result.config_enc){result.config=P.configFor(await vault.open(result.config_enc));delete result.config_enc;}
          return respond(result);
        }
      }
      if(path.startsWith('/admin/')) {
        S.requireValue(token.length>=32&&token.length<=128,'Admin sign-in required',401);
        const auth={token_hash:tokenHash};await call('admin_context',auth);
        if(method==='POST'&&path==='/admin/logout')return respond(await call('admin_logout',auth));
        if(method==='GET'&&path==='/admin/users')return respond(await call('admin_users_list',auth));
        if(method==='POST'&&path==='/admin/users') {
          const username=string(data.username,64,'username').trim();S.requireValue(/^[a-zA-Z0-9_.-]{3,64}$/.test(username),'Username: 3–64 letters, numbers, dots, underscores or hyphens');
          const pw=string(data.password,256,'password');S.requireValue(pw.length>=10,'Password must contain at least 10 characters');
          const payload={...auth,username,device_limit:int(data.device_limit,1,100,'Device limit'),expires_at:int(data.expires_at,Math.floor(Date.now()/1000)+1,4102444800,'Expiry'),password_hash:await S.hashPassword(pw)};
          return respond(await call('admin_users_create',payload));
        }
        let m=path.match(/^\/admin\/users\/([^/]+)$/);
        if(m&&method==='PATCH') {
          const payload={...auth,id:uuid(m[1])};
          if(data.device_limit!==undefined)payload.device_limit=int(data.device_limit,1,100,'Device limit');
          if(data.expires_at!==undefined)payload.expires_at=int(data.expires_at,0,4102444800,'Expiry');
          if(data.enabled!==undefined)payload.enabled=int(data.enabled,0,1,'Enabled');
          if(data.password){const pw=string(data.password,256,'password');S.requireValue(pw.length>=10,'Password must contain at least 10 characters');payload.password_hash=await S.hashPassword(pw);}
          return respond(await call('admin_users_update',payload));
        }
        if(m&&method==='DELETE')return respond(await call('admin_users_delete',{...auth,id:uuid(m[1])}));
        m=path.match(/^\/admin\/users\/([^/]+)\/devices$/);
        if(m&&method==='GET')return respond(await call('admin_devices_list',{...auth,id:uuid(m[1])}));
        m=path.match(/^\/admin\/devices\/([^/]+)$/);
        if(m&&method==='DELETE')return respond(await call('admin_devices_delete',{...auth,id:uuid(m[1])}));
        if(method==='GET'&&path==='/admin/servers')return respond(await call('admin_servers_list',auth));
        m=path.match(/^\/admin\/servers\/([^/]+)$/);
        if(method==='POST'&&path==='/admin/servers'||method==='PATCH'&&m) {
          const payload={...auth};if(m)payload.id=uuid(m[1]);
          if(data.name!==undefined){payload.name=string(data.name,80,'server name').trim();S.requireValue(payload.name,'Server name required');}
          if(data.location!==undefined)payload.location=string(data.location,80,'location');
          if(data.enabled!==undefined)payload.enabled=int(data.enabled,0,1,'Enabled');
          if(data.uri) {
            let imported;try{imported=P.importURI(data.uri);}catch(e){throw new S.ApiError(400,e instanceof S.ApiError?e.message:'Invalid server link');}
            payload.config_enc=await vault.seal(imported.outbound);payload.protocol=imported.outbound.type;payload.name??=imported.name.slice(0,80);
          }
          if(method==='POST'){S.requireValue(payload.config_enc,'Server link required');payload.location??='';}
          return respond(await call(method==='POST'?'admin_servers_create':'admin_servers_update',payload));
        }
        if(m&&method==='DELETE')return respond(await call('admin_servers_delete',{...auth,id:uuid(m[1])}));
        if(method==='GET'&&path==='/admin/seed') {
          const result=await call('admin_seed',auth),profiles={};
          for(const item of result.profiles)profiles[item.id]=P.configFor(await vault.open(item.config_enc));
          return respond({seed:await new S.Vault(await vault.seedKey()).seal({...result,profiles})});
        }
      }
      throw new S.ApiError(404,'Endpoint not found');
    } catch(error) { return respond({error:error instanceof S.ApiError?error.message:'Backend error. Check the deployment and try again.'},error instanceof S.ApiError?error.status:500); }
  };
}

return {createHandler};
})();

const env = (name: string) => Deno.env.get(name) || '';
const url = env('SUPABASE_URL');
const secret = env('SUPABASE_SERVICE_ROLE_KEY') || JSON.parse(env('SUPABASE_SECRET_KEYS') || '{}').default;
const vault = new S.Vault(env('YAY_MASTER_KEY'));
const adminHash = env('YAY_ADMIN_PASSWORD_HASH');
if (!url || !secret || !adminHash.startsWith('pbkdf2$600000$')) throw new Error('Yay VPN backend secrets are missing');
const rpc = async (action: string, payload: unknown) => {
  const headers: Record<string,string> = { apikey: secret, 'Content-Type': 'application/json' };
  if (secret.split('.').length === 3) headers.Authorization = 'Bearer ' + secret;
  const response = await fetch(url + '/rest/v1/rpc/yay_rpc', {
    method: 'POST', headers, body: JSON.stringify({ p_action: action, p_payload: payload }),
    signal: AbortSignal.timeout(12000),
  });
  if (!response.ok) throw new Error('Database RPC unavailable');
  return await response.json();
};
Deno.serve(H.createHandler({rpc, vault, adminHash}));
