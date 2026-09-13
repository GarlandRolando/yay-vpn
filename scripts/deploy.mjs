import { spawn } from 'node:child_process';
import { readFile } from 'node:fs/promises';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
const root=resolve(dirname(fileURLToPath(import.meta.url)),'..');
const config=JSON.parse(await readFile(resolve(root,'.yay-local.json'),'utf8'));
if(!/^[a-z0-9]{10,40}$/.test(config.project_ref))throw Error('Invalid project reference');
const isWindows=process.platform==='win32';
async function run(args){await new Promise((accept,reject)=>{const child=spawn(isWindows?'npx.cmd':'npx',['--yes','supabase@2.39.2',...args],{cwd:root,stdio:'inherit',shell:isWindows});child.on('error',reject);child.on('exit',code=>code===0?accept():reject(Error('Supabase command failed. Read the error above.')));});}
console.log('Deploying to project '+config.project_ref+'. Apply the SQL migration in the dashboard first.');
await run(['secrets','set','--env-file','.yay-secrets.env','--project-ref',config.project_ref]);
await run(['functions','deploy','yay-api','--project-ref',config.project_ref,'--no-verify-jwt','--use-api']);
console.log('Deployment command completed. Check '+config.api_url+'/healthz');
