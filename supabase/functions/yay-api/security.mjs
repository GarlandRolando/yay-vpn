const encoder = new TextEncoder();
export class ApiError extends Error { constructor(status, message) { super(message); this.status = status; } }
export function requireValue(value, message, status = 400) { if (!value) throw new ApiError(status, message); }
export const bytes = value => encoder.encode(value);
export function base64(value) { let s=''; for (const b of value) s+=String.fromCharCode(b); return btoa(s); }
export function unbase64(value) { return Uint8Array.from(atob(value), c=>c.charCodeAt(0)); }
export const randomToken = () => base64(crypto.getRandomValues(new Uint8Array(32))).replaceAll('+','-').replaceAll('/','_').replaceAll('=','');
export async function sha(value) { const raw=typeof value==='string'?bytes(value):value; return Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',raw)),b=>b.toString(16).padStart(2,'0')).join(''); }
export function constantEqual(a,b) { if(a.length!==b.length)return false; let n=0; for(let i=0;i<a.length;i++)n|=a[i]^b[i]; return n===0; }
export async function hashPassword(password, salt=crypto.getRandomValues(new Uint8Array(16))) {
  const key=await crypto.subtle.importKey('raw',bytes(password),'PBKDF2',false,['deriveBits']);
  const hash=await crypto.subtle.deriveBits({name:'PBKDF2',hash:'SHA-256',salt,iterations:600000},key,256);
  return `pbkdf2$600000$${base64(salt)}$${base64(new Uint8Array(hash))}`;
}
export async function verifyPassword(password, stored) {
  try { const [type,iterations,salt,expected]=stored.split('$');
    if(type!=='pbkdf2'||iterations!=='600000')return false;
    const actual=(await hashPassword(password,unbase64(salt))).split('$')[3];
    return constantEqual(unbase64(actual),unbase64(expected));
  } catch { return false; }
}
export class Vault {
  constructor(master) { const raw=unbase64(master); requireValue(raw.length===32,'Invalid backend encryption key',503); this.raw=raw; }
  async aes() { return crypto.subtle.importKey('raw',this.raw,'AES-GCM',false,['encrypt','decrypt']); }
  async seal(value) { const iv=crypto.getRandomValues(new Uint8Array(12)); const encrypted=new Uint8Array(await crypto.subtle.encrypt({name:'AES-GCM',iv,additionalData:bytes('yay-vpn-v1')},await this.aes(),bytes(JSON.stringify(value)))); const result=new Uint8Array(12+encrypted.length);result.set(iv);result.set(encrypted,12);return base64(result); }
  async open(value) { const raw=unbase64(value);const decrypted=await crypto.subtle.decrypt({name:'AES-GCM',iv:raw.slice(0,12),additionalData:bytes('yay-vpn-v1')},await this.aes(),raw.slice(12));return JSON.parse(new TextDecoder().decode(decrypted)); }
  async seedKey() { const key=await crypto.subtle.importKey('raw',this.raw,'HKDF',false,['deriveBits']);return base64(new Uint8Array(await crypto.subtle.deriveBits({name:'HKDF',hash:'SHA-256',salt:bytes('yay-vpn-supabase-v1'),info:bytes('encrypted-preload')},key,256))); }
}
// Android SHA256withECDSA produces ASN.1 DER; WebCrypto expects 32-byte r || 32-byte s.
export function derToRaw(der) {
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
export async function verifyDevice(request,path,raw,publicKey) {
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
