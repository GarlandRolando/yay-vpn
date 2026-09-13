import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
const root=resolve(dirname(fileURLToPath(import.meta.url)),'..');
let config;try{config=JSON.parse(await readFile(resolve(root,'.yay-local.json'),'utf8'));}catch{console.error('Run node scripts/setup.mjs first.');process.exit(1);}
if(!/^https:\/\/[a-z0-9]{10,40}\.supabase\.co\/functions\/v1\/yay-api$/.test(config.api_url))throw Error('Invalid API URL');
const files=new Map([['/','index.html'],['/app.js','app.js'],['/style.css','style.css']]);
const server=createServer(async(req,res)=>{
  const host=req.headers.host;
  if(!['127.0.0.1:8788','localhost:8788'].includes(host)||req.method!=='GET'){res.writeHead(403);res.end('Forbidden');return;}
  let body,type;
  if(req.url==='/config.js'){body=`window.YAY_API_BASE=${JSON.stringify(config.api_url)};`;type='text/javascript';}
  else if(files.has(req.url)){const name=files.get(req.url);body=await readFile(resolve(root,'admin',name));type=name.endsWith('.html')?'text/html':name.endsWith('.css')?'text/css':'text/javascript';}
  else {res.writeHead(404);res.end('Not found');return;}
  res.writeHead(200,{'Content-Type':type+'; charset=utf-8','Cache-Control':'no-store','X-Content-Type-Options':'nosniff','Referrer-Policy':'no-referrer',
    'Content-Security-Policy':`default-src 'self'; connect-src 'self' ${new URL(config.api_url).origin}; script-src 'self'; style-src 'self'; img-src 'self' data:; frame-ancestors 'none'; base-uri 'none'; form-action 'self'`});res.end(body);
});
server.on('error',e=>{console.error(e.code==='EADDRINUSE'?'Port 8788 is already in use. Close the other admin launcher first.':e.message);process.exit(1);});
server.listen(8788,'127.0.0.1',()=>console.log('Open http://127.0.0.1:8788 in your browser.\nThe admin screen runs here; accounts and servers remain in Supabase.\nKeep this terminal open while using the admin screen. Ctrl+C closes the screen server only.'));
