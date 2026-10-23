// Paste this complete module into a Cloudflare Worker. No keys or bindings needed.
// This is a fixed account-API relay, not an arbitrary URL or VPN traffic proxy.
const UPSTREAM='https://vjcpphrhdzdywmrfndta.supabase.co/functions/v1/yay-api';
const forwarded=['authorization','content-type','x-yay-time','x-yay-nonce','x-yay-signature','origin'];
const reply=(status,error)=>new Response(JSON.stringify({error}),{status,headers:{'Content-Type':'application/json','Cache-Control':'no-store'}});
async function limitedBody(request){
  if(!request.body)return undefined;
  const reader=request.body.getReader(),chunks=[];let size=0;
  try{while(true){const {value,done}=await reader.read();if(done)break;size+=value.length;if(size>32768){await reader.cancel();throw new RangeError('Request too large');}chunks.push(value);}}
  finally{reader.releaseLock();}
  const body=new Uint8Array(size);let offset=0;for(const chunk of chunks){body.set(chunk,offset);offset+=chunk.length;}return body;
}
export function createRelay(upstreamFetch=fetch){
  return async request=>{
    const url=new URL(request.url),path=url.pathname.slice('/yay-api'.length);
    if(!url.pathname.startsWith('/yay-api/')||url.search||!(path==='/healthz'||/^\/v1\/[A-Za-z0-9_/-]+$/.test(path)))return reply(404,'Endpoint not found');
    if(!['GET','POST','DELETE'].includes(request.method)||path==='/healthz'&&request.method!=='GET')return reply(405,'Method not allowed');
    const headers=new Headers();for(const name of forwarded){const value=request.headers.get(name);if(value!==null)headers.set(name,value);}
    try{
      const body=await limitedBody(request);
      const response=await upstreamFetch(new Request(UPSTREAM+path,{method:request.method,headers,body,redirect:'manual',signal:AbortSignal.timeout(12000)}));
      const output=new Headers({'Cache-Control':'no-store','CDN-Cache-Control':'no-store','X-Content-Type-Options':'nosniff'});
      for(const name of ['content-type','access-control-allow-origin','vary']){const value=response.headers.get(name);if(value!==null)output.set(name,value);}
      return new Response(response.body,{status:response.status,headers:output});
    }catch(error){return error instanceof RangeError?reply(413,'Request too large'):reply(502,'Account service could not be reached through the relay');}
  };
}
export default {fetch:createRelay()};
