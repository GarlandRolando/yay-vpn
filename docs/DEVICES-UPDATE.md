# Yay VPN 1.3: devices, remaining time and branding

The existing Supabase project has been updated: migration `user_device_management` and `yay-api` version 2 are live. Existing accounts and server records were preserved. You do not need to re-import the 135 servers or run the migration yourself on this project.

## Get the new apps

Start a **new** workflow run on `main`. Old artifacts do not contain these features.

- Android: **Build Yay VPN test APK** → download `Yay-VPN-test-APK` when green.
- Windows: **Build Yay VPN Windows test app** → download `Yay-VPN-Windows-x64-test` when green, extract everything, then open `YayVPN.exe`.
- iPhone/iPad/Mac: **Check Yay VPN Apple builds (unsigned)** checks the Apple targets. Its logs are downloadable even when compilation fails. It does not produce an installable signed iPhone app.

## Device settings

Log in and open **Settings → Devices & limit**.

- Your administrator's device limit appears with the number of occupied slots.
- Your current device is marked **This device**.
- Other registered devices show **Remove** on the right. Confirm removal to sign that device out and free its slot.
- Device names, short IDs and last-seen times help distinguish similar devices. Last seen means the last authorized backend request, not proof that the device is online now.
- Each unused slot has a large **+**. Tap it for instructions: install Yay VPN on the other device and log in with the same account. The cloud fills the slot automatically.
- The **+** does not raise the limit. Only the administrator can change it.
- If all slots are occupied and you cannot sign in on a new device, remove a device from an existing signed-in device, or ask the administrator to remove one.

Removing a device deletes its registration and all its sessions. The removed device cannot fetch another server configuration or renew its lease using the old token. An active tunnel stops after rejection or lease expiry, at most 180 seconds after the last valid grant. This is not an instantaneous upstream-provider disconnect. If someone knows the account password, ask the administrator to change it: removing a registration does not permanently ban that physical device from signing in again.

The current device cannot remove itself from this page. Use **Log out** to end its session; logout keeps its registration as before. An administrator or another signed-in device can remove its registration.

## Remaining access

The main screen shows **Time remaining** immediately above **Refresh locations**. It counts down days, hours, minutes and seconds using the server's account expiry and a monotonic local clock. This is calendar access time, not a pool of connection minutes; it continues while disconnected. A fresh login, catalog refresh or device refresh updates the expiry. The backend remains authoritative if the display and server differ.

## Logo

All three client codebases use the same original white **Y** on a red square, in the top header and platform app icons. Apple source covers both iPhone/iPad and macOS. `branding/yay-logo.svg` is the vector master; `scripts/generate-brand.py` reproduces the platform assets using Python and Pillow.

## Build repairs

- Android: removed `windowLightNavigationBar` from the base Android 8.0 theme. It requires API 27; the app retains minimum API 26 and the default light navigation icons on black.
- Apple: linked `libresolv.tbd` to both Packet Tunnel targets, resolving the missing `_res_9_nclose`, `_res_9_ninit` and `_res_9_nsearch` symbols found in the first iOS run.
- Apple logs now capture stdout and stderr into `build-logs`, so artifact upload does not exclude them as hidden files.
- Workflows use Node 24 action releases. Android uses the hosted runner's existing SDK.

## Validation and limits

- Updated Windows WPF client: `dotnet publish` for Windows x64 succeeded locally, including icons and the new controls. Real Windows VPN/device UI interaction remains to be tested.
- Android Java syntax and resources passed local checks. The prior build compiled its native engine and Java, then failed lint; a fresh complete Android build is still required for the new UI.
- Apple Swift syntax, project configuration and icon references passed local checks. The corrected Xcode builds still need to run on GitHub.
- All 15 HTTP/cryptography unit tests passed. Device authorization was also exercised in embedded PostgreSQL: cross-account access denied, current device protected, incorrect device keys and replay rejected, removal revoked sessions, and the freed slot could be reused without exceeding the cap.
- The repository's PostgreSQL CI also runs the device tests with the existing real PostgreSQL concurrency checks.
- After deployment, live SQL metadata confirmed that device management is installed and RPC execution remains restricted to `service_role`. No new table access was granted to app clients. The Supabase security advisor's seven informational “RLS enabled, no policy” findings are expected for the inaccessible private tables: https://supabase.com/docs/guides/database/database-linter?lint=0008_rls_enabled_no_policy

Test with two devices: remove the second from the first, confirm the second loses access, then sign in on another device to fill the empty slot. Also check English, Chinese and Indonesian labels, expiry countdown, and the new icons.
