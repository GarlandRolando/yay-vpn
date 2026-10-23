# Replace a server catalog — Yay VPN 1.7

This update accepts VLESS and Hysteria2 sing-box node JSON. It preserves the provider's UUID/password, TLS, REALITY, ECH, obfuscation and packet encoding. It does not change a server's actual protocol or invent missing TLS settings.

## Existing installation

Copy the updated `scripts`, `admin`, and `supabase/functions/yay-api` files into your existing Yay VPN folder. Keep your existing local configuration and encryption key. **Do not run setup again or reset the master key.** No database migration is needed for this update.

From CMD inside that existing folder:

```cmd
node scripts/deploy.mjs
node scripts/open-admin.mjs
```

Open http://127.0.0.1:8788, sign in, select **Servers → Import / replace server list**, choose your provider's JSON/TXT file and review the location counts. Click **Replace active servers** and keep the window open until it reports completion.

The importer validates the entire file before writing anything. It updates matching names, adds new names and then pauses old entries absent from the file. Paused entries disappear from the users' active catalog. If interrupted, rerun the same file; check the panel if another administrator was editing concurrently. This is a series of requests, not a single database transaction. During the import, users may temporarily see both catalogs; existing sessions on updated/paused entries can disconnect at the next access check. Old entries are retained paused for review, not permanently deleted. Overwritten credentials are not backed up by this import.

## CMD alternative

Save the provider file as `new-servers.json` in the project folder, then:

```cmd
node scripts/replace-servers.mjs "new-servers.json"
node scripts/replace-servers.mjs "new-servers.json" --apply
```

The first command is a preview. The second asks for your admin password with hidden input. Do not place the password in the command or upload the provider file to GitHub.

## Android and Windows

Build the 1.7 update using the existing Android and Windows GitHub Actions workflows on `main`. Install the new APK / Windows package. Disconnect before refreshing, then tap **Refresh locations**. Countries are derived from active cloud entries; there is no embedded endpoint list to replace in source. Poland, France, Belgium and Switzerland have flags and translated labels.

Hysteria2 uses QUIC, so the country TCP scan skips those nodes. Its UI says **Test on connect**, and Enhanced mode includes Hysteria2 candidates even without a TCP result. Auto's existing native proxy checks also handle these nodes. A stored configuration or successful TCP ping is not proof of working VPN traffic.

## Provider settings to verify

If a Hysteria2 node has no `tls.server_name`, its certificate must validate for its server address. Ask the provider for the correct SNI/certificate settings if it fails. Do not guess an SNI or turn off TLS verification. ECH with only `enabled: true` also depends on usable DNS ECH discovery; an IP-only entry may need the provider's hostname or explicit ECH config.

After import, test one node of each protocol and several locations on the user's actual network. Storage verification does not test remote endpoint health, credentials, certificates or local UDP availability.
