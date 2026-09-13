# Yay VPN

Yay VPN clients with a black interface, red world map when disconnected, green map after a successful VPN internet check, country-only location selection, and cloud-managed access.

## Windows, iPhone, iPad and Mac

Native Windows x64 and Apple source targets are now included. The Windows .NET client passes a local Windows-targeted publish build; its complete engine bundle and device tests are still pending. Apple native compilation and device tests are also pending. They use your existing Supabase accounts and server list.

- Windows: Actions → **Build Yay VPN Windows test app** → **Run workflow**.
- Apple: Actions → **Check Yay VPN Apple builds (unsigned)** → **Run workflow**. This produces compile logs; installing an Apple VPN requires eligible signing and provisioning.
- Follow [the beginner platform guide](docs/WINDOWS-AND-APPLE.md) for download, build and signing steps.

## Build the Android app

1. Open this repository's **Actions** tab.
2. Select **Build Yay VPN test APK** and click **Run workflow**.
3. Keep the prefilled backend URL and run the workflow on `main`.
4. Open the completed green run and download **Yay-VPN-test-APK** from Artifacts.
5. Extract the archive, transfer the APK to an Android 8+ arm64 phone, install it, and use a regular Yay account to sign in.

This is a test build. Native compilation, phone installation, and VPN traffic must pass before distribution. A failed build does not produce a usable APK; send the failing step's log for repair. GitHub Actions on a private repository uses the account's included minutes; keep paid usage disabled to avoid charges. No automated APK runs are scheduled by this workflow.

The workflow builds the pinned sing-box native library and the Android app. Your Supabase URL is public configuration. Never upload admin passwords, service keys, local secret files, or plaintext VPN server lists to this repository.

## User experience

- Start screen: **Log in** or **Sign up**.
- Signup opens contact options: copy Weixin ID, open WhatsApp, or open email. Accounts are created by an administrator; there is no automatic self-registration.
- Login uses a regular Yay username and password. Device limits and expiry are checked by the cloud backend.
- Main screen shows countries and flags, never individual server names or endpoint addresses.
- Expand the country picker and tap **Test country pings** to measure all available servers. Each country shows its best real TCP connection measurement, or an unavailable / untested state.
- Tap the power button to connect. If necessary, the selected country's servers are measured first. The app chooses among the fastest servers and tries up to three candidates if the VPN connectivity check fails.
- Settings at top left: Help, language (English, Chinese, Indonesian), and Log out. The back arrow returns to the main screen.

## Android latency and connection details

The displayed measurement is **TCP ping**, analogous to v2rayNG's TCP connection-time test. It is not ICMP, throughput, or a full authenticated proxy delay. HTTPS through the actual Android VPN network is checked separately before the map turns green. A failed or intercepted HTTP check will not be shown as connected.

Measurements are from the phone's physical network, use a 1.5-second TCP connect timeout, and expire after two minutes or a physical network / profile revision change. DNS and cloud authorization time are not included in the displayed TCP time. Six tests run concurrently; the country refresh costs one authorized backend request per profile (135 requests for the current full list). Tests are user-triggered or run for the selected country when connecting; there is no recurring full-list poll.

Tie selection is randomized only within `max(10 ms, 5% of the minimum)` of the best result. Slower servers remain ordered as fallback candidates. With only one successful candidate, it is selected directly. TCP reachability alone cannot verify a subscription, password, or REALITY key.

The existing backend converts VLESS / VMess / Trojan / supported Shadowsocks links to sing-box configurations. The native core handles protocol negotiation, TLS / REALITY, transport, DNS, and the Android TUN. The reference v2rayNG APK is not embedded, renamed, or used as a native dependency. Hysteria2 remains unsupported by the Yay import form.

The 135 cloud profiles are a snapshot of the provider's subscription, not automatically refreshed subscription content. User device registration limits are enforced by Yay's backend, not by the upstream provider's shared credentials.

## Current deployment

Backend: `https://vjcpphrhdzdywmrfndta.supabase.co/functions/v1/yay-api`

The UI update requires no new cloud migration or secret rotation. Existing users, server records, and access limits remain authoritative. Use the local admin panel from the original Supabase package to manage them.

See `docs/UI-AND-PHONE-CHECKS.md` for acceptance checks and `GUIDE.md` for backend setup. This code is GPL-3.0-or-later; retain the included licenses and provide corresponding source when distributing builds.
