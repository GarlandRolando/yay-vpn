// Builds the single-file dashboard deployment alternative from the same tested modules.
import { readFile,writeFile } from 'node:fs/promises';
import { resolve,dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
const root=resolve(dirname(fileURLToPath(import.meta.url)),'..'),dir=resolve(root,'supabase/functions/yay-api');
async function wrap(file,name){let source=await readFile(resolve(dir,file),'utf8');const names=[...source.matchAll(/export\s+(?:async\s+)?(?:function|class|const)\s+(\w+)/g)].map(m=>m[1]);source=source.replace(/^import .*;\n/gm,'').replaceAll('export ','');return `const ${name}=(()=>{\n${source}\nreturn {${names.join(',')}};\n})();\n`;}
let body='// Yay VPN — Supabase Dashboard single-file alternative. Generated; do not edit modules here.\n';
body+=await wrap('security.mjs','S');
body+='const requireValue=S.requireValue;\n';
body+=await wrap('profiles.mjs','P');
body+=await wrap('handler.mjs','H');
let entry=await readFile(resolve(dir,'index.ts'),'utf8');entry=entry.replace(/^import .*;\n/gm,'').replace('new Vault(','new S.Vault(').replace('Deno.serve(createHandler(','Deno.serve(H.createHandler(');
body+=entry;await writeFile(resolve(root,'supabase/PASTE-INTO-EDGE.ts'),body);
console.log('Created supabase/PASTE-INTO-EDGE.ts');
