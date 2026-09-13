import { createInterface } from 'node:readline/promises';
import { Writable } from 'node:stream';
import { stdin, stdout } from 'node:process';
import { readFile, writeFile, access } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { resolve, dirname } from 'node:path';
import { hashPassword, base64 } from '../supabase/functions/yay-api/security.mjs';
const root=resolve(dirname(fileURLToPath(import.meta.url)),'..');
try {await access(resolve(root,'.yay-secrets.env'));console.error('Setup already exists. Do not replace your encryption key. See GUIDE.md for reconnecting an existing project.');process.exit(1);}catch(error){if(error.code!=='ENOENT')throw error;}
console.log('\nYay VPN · Supabase setup\nThis runs locally. It does not upload passwords or keys.\n');
let mute=false;
const output=new Writable({write(chunk,encoding,callback){if(!mute)stdout.write(chunk,encoding);callback();}});
const rl=createInterface({input:stdin,output,terminal:Boolean(stdin.isTTY)});
async function secret(prompt){stdout.write(prompt);mute=true;const answer=await rl.question('');mute=false;stdout.write('\n');return answer;}
try {
  let ref=(await rl.question('Paste your Supabase project URL or reference ID: ')).trim();
  if(ref.startsWith('https://')){const u=new URL(ref);if(!/^[a-z0-9]{10,40}\.supabase\.co$/.test(u.hostname))throw Error('Use your project URL from Supabase, e.g. https://abcdefghijklmnopqrst.supabase.co');ref=u.hostname.split('.')[0];}
  if(!/^[a-z0-9]{10,40}$/.test(ref))throw Error('Project reference must be the ID from your Supabase dashboard.');
  const password=await secret('Choose an ADMIN password (14–256 characters; hidden while typing): ');
  if(password.length<14||password.length>256)throw Error('Use 14–256 characters.');
  if(password!==await secret('Repeat the ADMIN password: '))throw Error('Passwords do not match.');
  const hash=await hashPassword(password),master=base64(crypto.getRandomValues(new Uint8Array(32)));
  const api=`https://${ref}.supabase.co/functions/v1/yay-api`;
  await writeFile(resolve(root,'.yay-secrets.env'),`YAY_MASTER_KEY=${master}\nYAY_ADMIN_PASSWORD_HASH='${hash}'\n`,{mode:0o600,flag:'wx'});
  await writeFile(resolve(root,'.yay-local.json'),JSON.stringify({project_ref:ref,api_url:api},null,2),{mode:0o600});
  await writeFile(resolve(root,'android/yay.properties'),`API_BASE_URL=${api}\n`);
  console.log('\nCreated .yay-secrets.env and local project settings.\nKeep the secrets file private and back it up.\nFollow GUIDE.md to apply the SQL and deploy the Edge Function.\nYour APK backend URL: '+api);
} catch(error){console.error(error.message);process.exitCode=1;}finally{rl.close();}
