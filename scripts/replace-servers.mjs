import { readFile } from 'node:fs/promises';
import { resolve,dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { prepareCatalog,replaceCatalog } from './catalog-import.mjs';

const root=resolve(dirname(fileURLToPath(import.meta.url)),'..');
async function password() {
  if(!process.stdin.isTTY)throw Error('Run this command in an interactive terminal to enter your admin password.');
  process.stdout.write('Admin password (hidden): ');process.stdin.setRawMode(true);process.stdin.resume();process.stdin.setEncoding('utf8');
  return new Promise((accept,reject)=>{let value='';const done=()=>{process.stdin.off('data',onData);process.stdin.setRawMode(false);process.stdin.pause();process.stdout.write('\n');};
    function onData(chunk){for(const ch of chunk){if(ch==='\u0003'){done();reject(Error('Cancelled.'));return;}if(ch==='\r'||ch==='\n'){done();accept(value);return;}if(ch==='\u007f'||ch==='\b')value=value.slice(0,-1);else if(ch>=' ')value+=ch;}}
    process.stdin.on('data',onData);
  });
}
try {
  const args=process.argv.slice(2),file=args.find(a=>!a.startsWith('--'));
  if(!file||args.some(a=>a.startsWith('--')&&a!=='--apply'))throw Error('Usage: node scripts/replace-servers.mjs "new-servers.json" [--apply]');
  const {entries,warnings}=prepareCatalog(JSON.parse(await readFile(resolve(file),'utf8')));
  const counts={};for(const e of entries)counts[e.location]=(counts[e.location]||0)+1;
  console.log('Validated '+entries.length+' node configurations:');console.table(counts);
  if(warnings.length)console.log(warnings.length+' provider-setting warnings:\n'+warnings.join('\n'));
  if(!args.includes('--apply'))console.log('Preview only. Add --apply to replace the active cloud catalog. No connectivity test was performed.');
  else {
    const config=JSON.parse(await readFile(resolve(root,'.yay-local.json'),'utf8'));
    const base=new URL(config.api_url);
    if(base.protocol!=='https:'||base.username||base.password||base.search||base.hash||base.pathname.replace(/\/$/,'')!=='/functions/v1/yay-api'||!base.hostname.endsWith('.supabase.co'))throw Error('Invalid Supabase API URL in .yay-local.json');
    let token='';const api=async(path,method='GET',body)=>{
      const response=await fetch(base.href.replace(/\/$/,'')+path,{method,redirect:'error',signal:AbortSignal.timeout(20000),headers:{'Content-Type':'application/json',...(token?{Authorization:'Bearer '+token}:{})},body:body===undefined?undefined:JSON.stringify(body)});
      const data=await response.json();if(!response.ok)throw Error(`Cloud request failed (${response.status}): ${data.error||'Unknown error'}`);return data;
    };
    const health=await api('/healthz');if(!health.capabilities?.includes('node-json-v1'))throw Error('Deploy the updated backend first: node scripts/deploy.mjs');
    console.log('Target: '+base.hostname+'. Old locations absent from the file will be paused after replacement uploads succeed.');
    token=(await api('/admin/login','POST',{password:await password()})).token;
    try{const result=await replaceCatalog(entries,api,console.log);console.log('DONE:',result);console.log('Catalog storage verified. Actual VPN connectivity has NOT been tested. Refresh locations in the updated Android/Windows app.');}
    finally{try{await api('/admin/logout','POST',{});}catch{}token='';}
  }
} catch(error){console.error(error.message);console.error('If an import was interrupted, rerun it to finish. Do not reset your encryption key.');process.exitCode=1;}
