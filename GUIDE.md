# Yay VPN on Supabase — beginner guide

Use **this Supabase edition**, not the original Azure/Docker ZIP. This version uses a free Supabase project for the database and backend. Your admin screen runs locally on your laptop and manages the cloud data. Closing the admin screen does not stop the cloud backend.

Status: source and deployment materials prepared. 14 JavaScript/API/security tests and 8 SQL behavior/permission checks passed locally (SQL checks used PGlite/PostgreSQL WASM). A separate real-PostgreSQL CI workflow is included for concurrency testing. No Supabase cloud deployment or Android APK build has been completed in this environment. Follow the checkpoints below; do not treat source files as an installed VPN.

## What you need

- A free Supabase account and project.
- A Windows laptop (the commands also work with small path changes on macOS/Linux).
- Node.js **24 LTS** from https://nodejs.org — free.
- A GitHub account for the included Android build workflow, using your included Actions allowance.
- At least one working VPN node link, such as `vless://…`, from your existing provider.
- Your Android phone for the final real-connection test.

You do not need Azure, a purchased domain, a separate paid database, Docker on your laptop, or a Supabase Pro plan for this pilot. The actual upstream VPN service and bandwidth are provided by your nodes; they are not included in Supabase's allowance.

## 1. Create the Supabase project

1. Visit https://supabase.com/dashboard and sign in.
2. Create an organization on the **Free** plan if you do not already have one.
3. Select **New project**.
4. Use project name **yay-vpn**.
5. Generate a strong **database password** and keep it in your password manager. This is separate from your Yay VPN admin password and app-user passwords.
6. Choose **Southeast Asia / Singapore** if available. This chooses where your project database lives; it does not guarantee reachability from China.
7. Keep the Data API enabled; the backend calls a restricted database function through it.
8. Create the project and wait until it is ready.
9. Open the project's **Connect** dialog or **Settings → Data API** and copy the **Project URL**. It looks like `https://abcdefghijklmnopqrst.supabase.co`.

The project reference is the `abcdefghijklmnopqrst` part. It is not a secret. Do not share your database password, access token, secret/service-role key, or master encryption key.

**Checkpoint:** The dashboard shows your project is running, and you have its Project URL.

If Supabase is connected in ChatGPT, share only this Project URL/project name so the correct target can be selected. Do not create additional projects if a dedicated Yay VPN project already exists.

## 2. Extract the new ZIP and install Node.js

1. Extract `Yay-VPN-Supabase.zip` into Downloads.
2. Install Node.js 24 LTS from the official site, then close and reopen PowerShell.
3. Open PowerShell and run:

```powershell
node --version
```

You should see a version beginning `v24.`.

Enter the extracted project folder. For a ZIP extracted directly into Downloads:

```powershell
cd "$env:USERPROFILE\Downloads\Yay-VPN-Supabase"
```

Your path may contain an extra outer folder if Windows created one. Run `dir` and make sure you can see `GUIDE.md`, `scripts`, `supabase`, and `android` together before continuing.

Run setup:

```powershell
node scripts/setup.mjs
```

It asks for:

- Your **Supabase Project URL** or reference ID.
- A new **Yay VPN admin password**, 14–256 characters. Typing is hidden.
- The same admin password again.

The script creates:

| File | Purpose |
|---|---|
| `.yay-secrets.env` | Private encryption key and hashed admin password; never commit or share |
| `.yay-local.json` | Your project address for the admin launcher |
| `android/yay.properties` | Your backend address for local Android builds |

Keep the secrets file and admin password backed up privately. Setup refuses to overwrite an existing secrets file because replacing its encryption key would make stored server configurations unreadable.

**Checkpoint:** Setup reports success and prints an APK backend URL ending `/functions/v1/yay-api`.

## 3. Create the database tables

The old SQLite database is not imported into Supabase. This package includes the PostgreSQL migration you need.

1. Open `supabase/migrations/202609130001_yay_vpn.sql` in Notepad or a code editor.
2. Copy the entire file.
3. In your Supabase project, open **SQL Editor → New query**.
4. Paste the SQL.
5. Run it.

Expected result: a success message, usually with no returned rows.

The migration creates a private `yay_private` schema, tables, indexes, and a server-only `public.yay_rpc` function. It enables row-level security. **Do not disable row-level security or grant `anon`/`authenticated` access to the private schema/RPC.**

It intentionally creates no demo users, passwords, or active VPN nodes. Manage those through your admin screen later.

If you want to see the tables in the dashboard, choose the `yay_private` schema where the dashboard offers a schema selector. They may not appear under the default `public` selection. Do not add `yay_private` to the Data API's exposed schemas.

**Checkpoint:** This query returns `false`:

```sql
select has_function_privilege('anon', 'public.yay_rpc(text,jsonb)', 'execute');
```

False is correct: anonymous callers cannot access the privileged database function.

## 4. Deploy the Edge Function

An Edge Function is the small cloud program handling login, account checks and server delivery. Its name must be **yay-api**.

### Recommended: terminal deployment

In the project folder, authenticate the Supabase CLI:

```powershell
npx.cmd --yes supabase@2.39.2 login
```

Follow the CLI's browser sign-in flow. Keep any personal access token local; do not paste it into chat. The CLI version is pinned to match the supplied commands and has `--use-api` support, so no Docker installation is needed.

Then run:

```powershell
node scripts/deploy.mjs
```

This uploads `.yay-secrets.env` as Edge Function secrets and deploys the code through Supabase's Management API. It does not create a paid plan or apply the SQL for you; Step 3 must already be complete.

Supabase automatically supplies its backend project URL and server-side credentials to the function. You do not put a secret/service-role key into your Android app or admin JavaScript.

The deployment deliberately disables **Supabase JWT verification only for `yay-api`**. Yay VPN uses its own admin/app sessions, and app requests additionally require Android device signatures. The function itself verifies these before reading protected data. JWT verification and database row-level security are different controls; keep database RLS enabled.

### Alternative: paste the function through the dashboard

If the CLI sign-in or deployment is blocked:

1. Open **Edge Functions → Secrets** in your project.
2. Add `YAY_MASTER_KEY` and `YAY_ADMIN_PASSWORD_HASH` using the values from `.yay-secrets.env`. When copying the password hash into a dashboard field, omit the surrounding single quotes used by the `.env` format.
3. Choose **Deploy a new function → Via Editor**.
4. Name it **yay-api**.
5. Replace the template with the entire contents of **`supabase/PASTE-INTO-EDGE.ts`**. This is a generated single-file copy of the same tested code, not an alternative implementation.
6. Deploy it.
7. In that function's settings, disable **Verify JWT / Enforce JWT verification** for this function. Otherwise Yay VPN's custom session tokens will be rejected before its handler runs.

### Check deployment

Open your actual URL in a browser:

```text
https://YOUR_PROJECT_REF.supabase.co/functions/v1/yay-api/healthz
```

Expected response:

```json
{"ok":true,"service":"Yay VPN Supabase","version":1}
```

This confirms the function starts; the next admin-login test confirms database access.

If the response is 401 with a JWT error, check this function's JWT setting. If it is a startup/500 error, check the Edge Function logs, two `YAY_` secrets, and SQL migration. Keep private keys/passwords out of shared screenshots.

**Checkpoint:** `/healthz` returns the JSON shown above.

## 5. Open your admin screen

In PowerShell, from the project folder:

```powershell
node scripts/open-admin.mjs
```

Open **http://127.0.0.1:8788** in your browser. Keep that PowerShell window running while using the admin screen.

Sign in with the **Yay VPN admin password you chose in Step 2**. This is not your Supabase account password or database password.

The page is served locally, but it sends authenticated requests over HTTPS to your Supabase function. Its account/server data is stored in Supabase. No separate admin-site hosting bill is needed. Closing the launcher only closes this local screen; the cloud backend remains deployed.

**Checkpoint:** You see the People and Servers sections without a login/database error.

## 6. Add your app users

Select **People → Add person**.

| Field | Example / meaning |
|---|---|
| Username | `garland01` |
| Password | A new unique app-user password, at least 10 characters |
| Maximum registered devices | `2` allows two app installations to register |
| Access ends | Pick the user's expiry date/time in your local timezone |

The backend hashes the password automatically. All enabled app users can access all enabled server nodes in this version.

Device slots count registered app installations. Signing out does not free a slot. Reinstalling the app creates a new key and needs another slot. Use **People → Devices → Remove** to free an old registration. If someone untrusted still knows the account password, reset the password as well.

To renew someone, edit their expiry. To pause access, use Pause. There is no payment gateway or automatic subscription billing.

**Checkpoint:** You have one active app user whose expiry is in the future.

## 7. Add your working VPN node

Select **Servers → Add server**.

| Field | Example / meaning |
|---|---|
| Friendly name | `Singapore 01` — shown in the phone dropdown |
| Location | `Singapore` |
| Server link | A complete working `vless://…`, `vmess://…`, `trojan://…`, or `ss://…` node |

A provider subscription URL beginning `https://…` is not an individual node. Open it in your existing VPN client, then use that client's share/export function to obtain one node link. Confirm that node already works before testing Yay VPN.

Supported transports: TCP, WebSocket, and gRPC. VLESS requires TLS or REALITY. TLS certificate-verification bypasses and unsupported link parameters are rejected.

The backend encrypts the outbound profile before saving it. The admin listing shows name/location/protocol rather than the stored credential.

**Checkpoint:** One enabled server is visible in the admin screen.

## 8. Build the Android APK for THIS backend

The key connection between Android and Supabase is this full backend address:

```text
https://YOUR_PROJECT_REF.supabase.co/functions/v1/yay-api
```

Do not use just `https://YOUR_PROJECT_REF.supabase.co`, and do not add a trailing slash. The Supabase edition changes the Android build configuration to accept the full function address. The original Azure edition does not.

### GitHub Actions

1. Create a **private** GitHub repository, such as `yay-vpn-supabase`.
2. Upload the project contents to the repository root, including `.github` and its workflow files. Do not upload the ZIP itself or nest the files inside an extra folder.
3. Exclude `.yay-secrets.env`, `.yay-local.json`, signing keys, `.pem` files, and `android/yay.properties`. The included `.gitignore` handles git-based uploads but cannot protect manual drag-and-drop uploads. Safest: upload from a fresh extraction of the original downloaded ZIP, which contains no live secrets.
4. Open **Actions → Test Supabase backend**. Run it, or inspect the automatic run triggered by the upload. It tests real PostgreSQL transaction behavior in a disposable database, not your production project.
5. Then open **Actions → Build Yay VPN test APK → Run workflow**.
6. Enter the full backend URL above as `api_url`.
7. Wait for native engine compilation, Android compilation and lint.
8. If the build succeeds, download the **Yay-VPN-test-APK** artifact and extract `app-debug.apk`.

Stay within GitHub's included build allowance and do not enable paid overages. If GitHub requires account verification or a build fails, resolve that before expecting an APK. The native build workflow has not been run in this authoring session.

### Local Android Studio route

Use OpenJDK 17, Gradle 8.13, Android SDK 35, NDK 28.0.13004108, Go 1.24.10, and the supplied scripts. The native engine is pinned to sing-box 1.12.12 at commit `54ed58499d7063136ed52dabf87d179d252425d0`. It is retrieved at build time and is not bundled as an AAR in this ZIP.

Set `ANDROID_HOME` and `ANDROID_NDK_HOME`, run `bash scripts/build-core.sh`, then open `android/` in Android Studio. For Windows beginners, the Actions workflow avoids local Go/NDK setup.

**Checkpoint:** A successful build has produced an APK. A source ZIP alone does not satisfy this checkpoint.

## 9. Install and test the real VPN

1. Transfer `app-debug.apk` to your Android phone.
2. Allow installation from your chosen file manager when Android asks.
3. Open Yay VPN and sign in with the app user from Step 6.
4. Confirm the server list loads.
5. Choose the server, tap Connect, and grant Android's VPN permission.
6. Confirm websites load and your public IP changes to the upstream VPN exit IP.
7. Verify disconnect restores normal internet routing.
8. Test device limits with another device, then test account pause and expiry while connected.

A running tunnel icon is not enough: actual traffic, DNS and IPv6 behavior must be checked. Run `docs/PHONE-TESTS.md` for the complete acceptance checklist. This version has no always-on kill switch and does not automatically reconnect after boot.

From China, test the Supabase backend URL with your VPN DISCONNECTED before relying on it. A user must reach the login API before connecting. Free provider domains may be unreachable on some networks; cloud region alone does not fix that.

**Checkpoint:** Your real phone can sign in, establish the VPN, move traffic through the chosen node, and disconnect correctly.

## 10. Day-to-day management

Run `node scripts/open-admin.mjs` whenever you want to manage the service. Update accounts and nodes in the panel. Clients refresh the current server list when opened or when Refresh servers is pressed. Adding a server does not require a new APK. Changing the current server's configuration asks connected clients to reconnect at their next access check.

Optional preload: in Servers, download `seed.enc`, place it in `android/app/src/main/assets/` before building, and include it deliberately in your build source if using git. It is encrypted and its decryption key is delivered only after authenticated login. Skip this during initial setup; normal login already downloads and encrypts current data.

## Free usage and availability

Supabase's documented Free allowances include 500 MB database capacity per project, 5 GB egress, and 500,000 Edge Function invocations per month. These are plan allowances, not a promise of permanent free production service. Monitor the Usage page; provider policies and quotas can change.

The app checks access every 45 seconds while connected. Each check is one Edge Function invocation, even though that invocation may make several internal database calls. Twenty devices connected two hours a day make about 96,000 checks per 30 days, plus login/admin calls. Ten devices connected continuously make about 576,000 checks per 30 days, exceeding the free invocation allowance.

If the function cannot renew access, the app ends the VPN after its outstanding lease of at most 180 seconds, subject to Android scheduling. Quota exhaustion, a paused free project, or a blocked backend domain therefore affects active users. The database storage cap does not reset or delete your users monthly.

This backend controls distribution of VPN settings. It does not provision individual traffic-server credentials. A modified client can retain an extracted shared upstream key and bypass app-side expiry; strict traffic-level limits require enforcement by your actual VPN provider.

## Backups and recovery

Keep `.yay-secrets.env` private and backed up. Losing `YAY_MASTER_KEY` means saved server profiles can no longer be decrypted. Do not rerun setup with a new key against the same live database.

Use Supabase/PostgreSQL database backup/export tooling to back up `yay_private` and its functions. Do not assume free projects provide the same restore facilities as paid plans. If you cannot back up immediately, at least keep a separate private record of the source node links and account settings so you can rebuild them.

If moving laptops, copy the private local settings/secrets through a secure channel; install Node and run the admin launcher. If only `.yay-local.json` was lost, recreate it with the public project reference and full function URL; keep the original master key. If the admin password is lost, generate a new password hash locally and update only `YAY_ADMIN_PASSWORD_HASH`, preserving `YAY_MASTER_KEY`; revoke existing admin sessions using the dashboard.

## Official references

- Dashboard function deployment: https://supabase.com/docs/guides/functions/quickstart-dashboard
- Edge Function secrets: https://supabase.com/docs/guides/functions/secrets
- Function authentication: https://supabase.com/docs/guides/functions/auth
- Free-plan quotas: https://supabase.com/docs/guides/platform/billing-on-supabase
