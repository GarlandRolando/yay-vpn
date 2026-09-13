import { createHandler } from './handler.mjs';
import { Vault } from './security.mjs';

const env = (name: string) => Deno.env.get(name) || '';
const url = env('SUPABASE_URL');
const secret = env('SUPABASE_SERVICE_ROLE_KEY') || JSON.parse(env('SUPABASE_SECRET_KEYS') || '{}').default;
const vault = new Vault(env('YAY_MASTER_KEY'));
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
Deno.serve(createHandler({rpc, vault, adminHash}));
