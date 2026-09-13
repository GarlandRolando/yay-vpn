# Real-phone acceptance checks — not yet executed

Use your own test account and a known-working upstream node. Do not infer any item passed from backend unit-test results.

1. Build/lint with the pinned toolchain. Install on arm64 Android; verify native engine loads. Test x86_64 emulator separately if supported.
2. Open app: inspect login layout at normal and large font settings, keyboard, error labels, back navigation, and system-bar insets. Sign in with an incorrect password, then a correct password.
3. Verify the top server selector, connect button, expiry date, and device count match admin data. Confirm no server URI or password is visible in the UI or logcat.
4. Confirm a fresh user can reach the control API before establishing a VPN from the intended country/network.
5. Tap Connect, deny Android VPN permission, and retry. Grant permission. Verify ongoing notification and its Disconnect action. Check Android 13+ with notification permission denied and granted.
6. Record public IPv4 before/after connection. Open several HTTPS sites and a streaming app. Verify outbound traffic actually uses the selected upstream. A Connected label alone is insufficient.
7. Test DNS queries, IPv6 traffic, UDP, and a DNS-leak test. Verify IPv6 is tunneled or safely fails rather than bypassing the VPN. Inspect any bootstrap DNS queries separately.
8. Test VLESS TLS, VLESS REALITY/Vision, VMess, Trojan, and Shadowsocks with real working nodes for each transport you plan to sell. An importer test does not establish protocol interoperability.
9. Switch Wi-Fi to mobile data and back. Lock the phone for several minutes. Test airplane mode and network restoration. Verify the interface monitor and socket protection continue working.
10. Force-stop the app; verify the OS VPN ends. This version restores normal routing and is not a kill switch. Reopen and reconnect. No automatic boot connection is expected.
11. Set device limit to 1. Sign in on one phone, then a second. Confirm second phone is refused. Re-login on the first phone; confirm it still uses one slot.
12. Sign out on the first phone; confirm the registration remains. Remove that device in admin; confirm another phone can register. Reinstallation should require a new slot.
13. While connected, pause the account. Confirm the client disconnects on the next successful heartbeat. Re-enable and re-login. Repeat with password reset, device removal, user deletion, and expiry in two minutes.
14. Block access to the control API while traffic still works through the upstream. Confirm the client ends its connection after its outstanding lease expires. Repeat with the phone locked to measure actual Android scheduling delays.
15. Change a server link, disable the server, and delete it while connected. Confirm the app requires reconnecting or choosing another server. Add a new server and refresh without reinstalling.
16. Build with an encrypted `seed.enc`. Verify no server data is visible before login; verify login loads the current catalog even if the seed is stale. Verify no offline connect is possible using an old cache.
17. Test rejected TLS certificates, wrong server credentials, unreachable server, expired API certificate, and malformed config. Confirm there is no certificate-verification bypass and no silent fallback to direct routing within the active tunnel.
18. Check Android 15/16, 16 KB page-size environments, app updates with the same signing key, and device-registration continuity after an update.

Record phone model, Android version, network, protocol/transport, and pass/fail for each applicable test. Keep secrets out of screenshots/logs when sharing failures.
