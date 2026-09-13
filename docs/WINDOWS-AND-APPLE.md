# Yay VPN: Windows, iPhone, iPad and Mac

## What is ready

This repository now contains native client source for Android, Windows x64, iPhone/iPad (iOS/iPadOS 16+) and Mac (macOS 13+). The Windows and Apple clients are new and have **not passed native compilation or device tests yet**. A source-code folder is not an installed app. The manual workflows below are the next build gates.

All clients use the same existing backend:

`https://vjcpphrhdzdywmrfndta.supabase.co/functions/v1/yay-api`

Your 135 imported server records and existing users remain in Supabase. No migration, re-import, admin secret embedded in an app, or new backend deployment is needed for these clients. A normal user signs in with their Yay username/password. Each device registers separately and consumes a device slot. Logging out does not release a registered device slot; the administrator removes old devices in the admin panel.

## 1. Make the Windows app first

1. Open https://github.com/GarlandRolando/yay-vpn/actions/workflows/windows-build.yml while signed into your GitHub account.
2. Click **Run workflow**, choose **main**, and click the green **Run workflow** button.
3. Open the new run. Wait until the build finishes. If a step is red, open it and copy the error output for repair. Do not try to install a failed build.
4. On a successful run, scroll to **Artifacts** and download **Yay-VPN-Windows-x64-test**.
5. Right-click the downloaded ZIP, choose **Extract All**, and keep the complete extracted folder together.
6. Open **YayVPN.exe**. Windows requests administrator permission because a VPN needs to create the virtual network adapter. This test executable is not code-signed.
7. Click **Log in** and enter a regular Yay account. The administrator password is not a user login.
8. Choose a country, then press the power button. Allow the initial ping tests to finish. A green map means the app completed an HTTPS 204 check through its selected native proxy.
9. Use the power button to disconnect. Closing the Windows app stops its VPN engine.

Build target: Windows x64, .NET 8 WPF, with the runtime bundled. Use Windows 11 x64 for the initial test. This artifact does not target Windows ARM64 or 32-bit Windows. Keep `engine/sing-box.exe` beside the executable in its engine folder. Do not run only the EXE from inside a ZIP.

The app stores its device key and session token encrypted with Windows DPAPI for the current Windows user. It sends the authorized configuration to sing-box through standard input and does not write that configuration to a plaintext file. A watchdog enforces the backend lease and account expiry. This is not a system-wide always-on kill switch; Windows resumes its normal network route when Yay disconnects.

## 2. Check Apple compilation without purchasing anything

The Mac desktop platform is **macOS**. iPhone uses iOS; iPad uses iPadOS. They share SwiftUI source here, with separate iOS and macOS Packet Tunnel extensions.

1. Open https://github.com/GarlandRolando/yay-vpn/actions/workflows/apple-build.yml.
2. Choose **Run workflow → main → Run workflow**.
3. The workflow builds the native framework and compiles both Apple apps and both extensions without signing.
4. A successful check verifies compilation only. Its artifact contains logs, not an installable IPA. If it fails, share the first compiler error for repair.

These manual builds use your GitHub account's included Actions allowance. Mac runners consume that allowance faster than Linux. No scheduled builds or paid services are configured. Check the account's remaining allowance and keep paid usage disabled if you want zero charges.

## 3. Install an Apple development build using a Mac

This stage needs a Mac with Xcode and an Apple team/provisioning profile supporting Network Extensions. A free Personal Team must not be assumed to support this VPN entitlement. Do not purchase membership before the unsigned build has passed and you have decided how you want to distribute the app.

On your Mac:

1. Download this repository using **Code → Download ZIP**, then extract it.
2. Install Xcode from Apple and open it once to finish its setup. Install Go **1.24.10**, Git, and XcodeGen. XcodeGen's official installation instructions are at https://github.com/yonaskolb/XcodeGen.
3. Open Terminal in the extracted project folder and run:

```bash
bash scripts/build-apple-core.sh
xcodegen generate --spec apple/project.yml
open apple/YayVPN.xcodeproj
```

4. In Xcode, open **Settings → Accounts** and sign into your Apple developer account.
5. In the project editor, select each of the four targets. In **Signing & Capabilities**, select the same eligible team and enable automatic signing. Leave the Network Extensions and Keychain Sharing capabilities present.
6. If Apple says a bundle identifier is unavailable, choose a unique app identifier. Its extension identifier must be the app identifier plus `.tunnel`. Update `apple/project.yml` and regenerate to preserve the change. Keep the shared Keychain access group identical in the app and extension for each platform. Do not put signing keys in GitHub.
7. For iPhone/iPad, choose scheme **YayVPN-iOS**, attach the device, select it in Xcode and run. Follow Apple's device trust/Developer Mode prompts where required.
8. For Mac, choose **YayVPN-macOS** and **My Mac**, then run. Use the signed extension build. A standalone unsigned `.app` is not a working deployment of this Packet Tunnel client.
9. Open Yay VPN, log in with a regular user account and choose a country. Accept Apple's **Add VPN Configurations** system prompt. Tap the power button and test browsing.

The extension fetches a fresh grant itself, shares the device key/token through Keychain, renews access independently of the foreground UI, and stops after lease expiry or rejection. A small GPL-covered Go bridge performs the HTTPS check through sing-box's `proxy` outbound before Apple reports startup success. Reopening the app requests another check from the extension. This avoids treating a direct app URL request as proof that the proxy works.

## Apple distribution and cost

Apple currently lists Developer Program membership at **US$99 per year**, with local pricing where available. Apple's VPN App Store rule requires developers to be enrolled as an **organization**. Source generation and an unsigned compile check do not enroll you, buy membership, create certificates, or publish an app.

Before public Apple distribution, signing/provisioning, device tests, app icons, a reviewed privacy policy and pre-use data disclosure, and Apple's review requirements remain outstanding. This is not an App Store-ready package. An IPA cannot be installed on arbitrary iPhones just by sharing the file.

Primary references (checked 2026-09-13):
- Membership: https://developer.apple.com/support/compare-memberships/
- VPN review rule 5.4: https://developer.apple.com/app-store/review/guidelines/
- Packet Tunnel provider: https://developer.apple.com/documentation/networkextension/nepackettunnelprovider
- XcodeGen: https://github.com/yonaskolb/XcodeGen/blob/master/Docs/ProjectSpec.md

## The user interface

All clients provide login, manual signup contact options, a country selector, a power button over a translucent world map, and Help / Logout / English / Chinese / Indonesian settings. Contact options are Weixin `wxid_3lt1aad36ai822`, WhatsApp `+371 28 635 209`, and `dummystorage22@gmail.com`.

Country labels hide individual node names and addresses. TCP connection tests use a 1.5-second timeout and a two-minute cache. Windows tests up to six nodes concurrently; Apple currently tests sequentially. Desktop/Apple measurements may include DNS resolution time and are not identical to Android's physical-network TCP timing. Full-list testing performs one backend authorization request per server. It can take several minutes on Apple; choosing a country and connecting tests only that country's nodes. Country selection randomizes only near-tied fastest nodes, then tries up to three candidates. TCP success alone does not validate the provider's subscription credentials.

Server configurations are fetched after login and kept in process memory. Windows and Apple do not bundle the existing encrypted Android seed file. Users need cloud access to sign in, load locations, connect, and renew their access. New server records appear after **Refresh locations** or the next login; these clients do not have a live background catalog subscription. Your upstream subscription can expire or change independently of Yay user accounts.

## Required device acceptance checks

Use a test user with enough device slots and a short expiry. Check:

- Valid login, wrong password, expired account, and an extra device over the limit.
- Country flags and translations, narrow window/iPad rotation, keyboard and screen-reader access.
- A real VLESS REALITY server: browsing succeeds and the external IP changes to the selected VPN endpoint.
- Invalid credentials, unreachable node and failed HTTPS test: no false green success; a fallback is attempted.
- Wi-Fi change, offline/reconnect, sleep/wake and app backgrounding.
- Admin disables a server or account: active access stops within the existing lease, at most 180 seconds after the last valid grant. Account expiry must also stop it.
- Windows app exit/crash kills the child engine; Apple app backgrounding keeps the extension's lease checks running.
- Logout stops the tunnel and clears the local session.

Do not call these platform builds production-ready until these checks pass. The current design uses shared upstream provider credentials: Yay's app/device limits are not upstream Xray user limits.

## Validation recorded for this source update

On 2026-09-13, all 13 new C#, Swift and Go source files passed structural syntax parsing. Windows XML/XAML, Apple property lists/entitlements, project YAML, workflow YAML and the Apple shell script passed format/syntax checks. All 14 existing backend unit tests passed. These checks do not type-check .NET/Apple APIs, link Libbox, verify signing, render the native UI, or establish a real VPN connection. The manual native-build and device gates remain pending.
