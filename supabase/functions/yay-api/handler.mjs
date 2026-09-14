import * as S from './security.mjs';
import * as P from './profiles.mjs';
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
export function createHandler({rpc,vault,adminHash,allowedOrigins=['http://127.0.0.1:8788','http://localhost:8788']}) {
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
      if(method==='GET'&&(path==='/'||path==='/healthz'))return respond({ok:true,service:'Yay VPN Supabase',version:3});
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
        const resultToken=S.randomToken();
        const loginPayload={user_id:user.id,verified_hash:user.password_hash,public_key:publicKey,nonce_hash:nonceHash,
          device_name:string(data.device_name||'Android device',80,'device name'),token_hash:await S.sha(resultToken)};
        let result;
        if(data.replace_device_id!==undefined) {
          result=await call('login_replace',{...loginPayload,replace_device_id:uuid(data.replace_device_id)});
        } else {
          try {result=await call('login_finish',loginPayload);}
          catch(error) {
            if(!(error instanceof S.ApiError)||error.status!==409||!/Device limit/i.test(error.message))throw error;
            const replacement=await call('login_replace',{user_id:user.id,verified_hash:user.password_hash});
            if(replacement?.requires_device_replacement===true)return respond(replacement);
            throw error;
          }
        }
        return respond({...result,token:resultToken});
      }
      if(path.startsWith('/v1/')) {
        S.requireValue(token.length>=32&&token.length<=128,'Sign in to continue',401);
        const context=await call('user_context',{token_hash:tokenHash});const nonceHash=await S.verifyDevice(request,path,raw,context.public_key);
        const auth={token_hash:tokenHash,verified_public_key:context.public_key,nonce_hash:nonceHash};
        if(method==='GET'&&path==='/v1/bootstrap') {const result=await call('user_bootstrap',auth);return respond({...result,seed_key:await vault.seedKey()});}
        if(method==='GET'&&path==='/v1/devices')return respond(await call('user_devices_list',auth));
        const devicePath=path.match(/^\/v1\/devices\/([^/]+)$/);
        if(method==='DELETE'&&devicePath)return respond(await call('user_devices_delete',{...auth,id:uuid(devicePath[1])}));
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
