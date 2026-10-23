# Login without an existing VPN — Yay VPN 1.8

At least one configured login address must be reachable before the VPN can start. A healthy Wi-Fi connection does not prove that particular domain is reachable. A relay provides a second HTTPS address for the same authenticated account API. It does not guarantee reachability on any particular network.

## Free-plan candidate: Cloudflare Worker

Cloudflare Workers has a Free plan with 100,000 requests/day (https://developers.cloudflare.com/workers/platform/pricing/). This is an account-API relay; VPN user traffic does not pass through it. Quotas also cover bootstrap, access checks, device calls and connection grants. A free quota does not guarantee service availability or network reachability.

In https://dash.cloudflare.com/, open **Workers & Pages → Create application** and import the existing GitHub repository `GarlandRolando/yay-vpn`. Choose **Worker**, name `yay-vpn-login`, production branch `main`, and keep the repository root directory. The root `wrangler.jsonc` selects `relay/cloudflare-worker.mjs`. Using root directory `relay` also works with its own config. It has no npm dependencies, database bindings, or service-role keys to configure. Leave the build command empty and use the dashboard's Worker deployment command. Dashboard labels may vary.

Alternatively, for an existing basic Worker with an **Edit code** screen, replace its starter with the entire contents of `relay/cloudflare-worker.mjs` and deploy. The module has no imports.

Copy the actual public Worker URL. On the affected tablet, with other VPNs off, open `https://ACTUAL-WORKER-DOMAIN/yay-api/healthz`. It must show this backend's JSON containing `ok: true` and `service: "Yay VPN Supabase"`. If that platform domain is unreachable, a tested custom domain/other host is still required; another platform name alone is not a confirmed fix.

The deployed original-project relay is `https://yay-vpn-login.rolandogarland.workers.dev/yay-api`. Version 1.8.1 build workflows include this backup by default. It has responded successfully from our test environment, but reachability without another VPN must still be verified on the user's network. For a replacement/custom domain, use `https://ACTUAL-WORKER-DOMAIN/yay-api` as `api_relay_url` in both workflows after testing it.

## Vercel alternative: deploy and test the relay first

The `relay` folder is a separate minimal Vercel project. It forwards only `/yay-api/healthz` and `/yay-api/v1/...` to this project's existing Supabase Edge Function. It does not forward the admin API and contains no database or service-role key. All request signatures, account/device checks, expiry and token checks still happen in the existing backend. It explicitly disables caching of account responses.

From CMD in the extracted repository:

```cmd
cd relay
npx.cmd --yes vercel@59.16.0 --prod
```

Select your existing team, create a **separate project** called `yay-vpn-login`, use the current `./` directory and accept the detected settings. Do not deploy this small folder over the existing download website: it does not contain the APK or Windows downloads. Copy the actual production alias printed by Vercel; do not guess it from the project name.

Vercel's connection needs access to the correct team for an agent deployment. If it returns 403, team access must be granted; a locally signed-in CLI may already have that permission on your own computer. No paid upgrade is performed by this source change. Vercel Hobby is restricted to personal, non-commercial use; use a plan/host suitable for a paid service rather than assuming Hobby is a free commercial hosting plan. See https://vercel.com/docs/plans/hobby.

On the affected tablet, switch off any other VPN and open:

```text
https://ACTUAL-DEPLOYMENT-DOMAIN/yay-api/healthz
```

You must see this project's JSON health response with `ok: true` and `service: "Yay VPN Supabase"`. A website page, 404, Vercel sign-in or a CAPTCHA is not a successful API test. The production API must allow ordinary public HTTPS requests. Do not embed deployment-protection bypass secrets in an app.

If this new domain also cannot open, do not rebuild expecting a fix. Attach a domain/host that actually works from the tablet's network and test again. A reachable landing page alone does not prove the forwarded API is reachable. Moving to another platform domain is not a guarantee of access in China.

## Build both clients using the tested address

After the tablet test succeeds, use GitHub Actions on `main`:

- Android: **Build Yay VPN test APK → Run workflow**. Keep `api_url` as the existing Supabase URL. Keep the populated `api_relay_url` default, or replace it with a tested custom HTTPS base ending `/yay-api`.
- Windows: **Build Yay VPN Windows test app → Run workflow**. Keep the same populated relay default, or use the same tested custom base URL.

The value ends at `/yay-api`, without `/healthz`. Only the endpoints configured in the reviewed build can receive credentials. The original-project workflows now fill the relay automatically even if their optional input is blank. Relevant main-branch source changes automatically build new Android/Windows artifacts; existing installations still need the new package.

Install the newly built 1.8.1 APK / Windows package. First-time login preserves the original direct backend as the first choice. A transport failure or HTTP 502/503/504 can try the relay once, using a freshly signed request. Android also retries malformed/non-API responses on the alternate address, instead of mislabeling an HTML gateway page as an expired account. Later requests prefer the successful route during that app process. Wrong password, access denied and device-limit replies are not retried on another host. HTTPS certificate validation and redirect refusal remain enabled.

For local builds, Android reads `API_RELAY_URL` from `android/yay.properties` or `YAY_API_RELAY_URL` from the environment. If neither exists and the direct URL is the original Yay project, it uses the deployed relay above; an explicit blank disables that local backup. Windows reads `YAY_API_RELAY_URL` during MSBuild, or the `YayApiRelayUrl` MSBuild property, with the same deployed relay as its default. For a Windows direct-only diagnostic build, set `-p:YayDirectOnly=true` and clear the relay environment/property. These are public HTTPS addresses, not API secrets. There is no database migration or server-list reimport for this change.

## Verification

Test a fresh app login with Wi-Fi and no other VPN, then fetch locations, connect, refresh account access and disconnect. Repeat on a second network if possible. Confirm wrong passwords/device limits still produce the expected server error. If the relay is unavailable, a network that can reach direct Supabase should still work through the bounded fallback. Real network tests and platform builds are separate from the unit tests in this repository.

Vercel reverse-proxy documentation: https://vercel.com/docs/routing/rewrites

## Android login diagnostics (1.8.1)

Tap **Check login connection** below Log in. It performs public health requests to both configured addresses in parallel using the same network selection as the account API, without passwords, tokens or device signatures. Copy result includes the app/Android version, whether Android reports an active VPN, and each address's `OK`, `DNS_FAILED`, `TIMEOUT`, `TLS_FAILED`, `CONNECTION_FAILED`, `HTTP n`, or unexpected-response result. Do this with other VPNs off first.

Before the Yay tunnel starts, account requests use Android's normal default route, rather than binding to a snapshot of a network. Tunnel startup/connected API routing and protected engine sockets remain separate. The screen distinguishes an accepted login followed by a failed account download, and preserves the backend's automatic-clock error. No authentication, expiry or device-limit checks are bypassed.

If both addresses fail on the device, this client update cannot make an unreachable host reachable. Deploy the fixed-target relay on a domain/host tested from that network, then build with that base URL. Do not disable TLS verification or copy service-role keys/provider credentials into the app. A passing health check verifies API reachability, not a valid login or working VPN nodes.
