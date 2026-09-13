# Yay VPN 1.2 acceptance checks

## Checks completed in the authoring environment

- Inspected supplied v2rayNG APK archive, including its native library inventory and embedded license file. SHA-256: d4fa9f6887aa4e2c315cf7ddb08d493ac00a05241ecfa39893948188d33e6c3a. This does not authenticate its publisher.
- Compared the TCP latency approach to v2rayNG 2.3.8's `SpeedtestManager.socketConnectTime`: https://github.com/2dust/v2rayNG/blob/2.3.8/V2rayNG/app/src/main/java/com/v2ray/ang/handler/SpeedtestManager.kt
- Checked the pinned native service API and upstream Android builder: https://github.com/SagerNet/sing-box/tree/v1.12.12/experimental/libbox and https://github.com/SagerNet/sing-box/blob/v1.12.12/cmd/internal/build_libbox/main.go
- Parsed all Android Java source with java-parser 2.3.3. This checks Java syntax, not Android API types or native bindings.
- Selection policy unit tests are included in the APK workflow: failed probes excluded, only close ties randomized, all failures yield no selected server. These tests have not run locally because a full JDK / Android SDK is unavailable.

## Required build and phone checks

1. GitHub workflow succeeds: native build, Android compilation, selection policy unit tests, lint, backend tests.
2. On a real arm64 Android phone, install the test APK. Check layout with default and large font size, small screen, and dark system bars. Check talkback labels for the power button and picker.
3. Switch English / Chinese / Indonesian, restart, and confirm preference persists. Open signup/help; confirm Weixin copies the exact ID, WhatsApp opens the correct recipient, and email opens the correct address. No messages should send automatically.
4. Sign in with a valid regular account. Test invalid credentials, account expiry, and registration on more than the allowed devices. Never use the administrator password as a regular account password.
5. Expand countries. Confirm each country appears once with its flag and no raw server name, IP, or credential. Untested values show a dash, failures show unavailable, successful tests show measured milliseconds.
6. Cancel tests; change Wi-Fi / mobile data; verify old measurements are invalidated. Cancel a connect-time scan; ensure no VPN starts afterwards. Leaving for Settings cancels the scan.
7. Choose a country, connect, and grant Android VPN permission. The map must remain red during startup and turn green only after the explicit VPN-network HTTP 204 check succeeds.
8. Test one bad REALITY profile in a country with a good profile: TCP can succeed on the bad profile, but the end-to-end check should fail and the service should try another candidate (up to three).
9. Check internet access, exit IP, DNS and IPv6 behavior on a real phone. Confirm disconnect restores normal routing. This version has no always-on kill switch.
10. Pause the account or selected server in admin, shorten expiry, or edit a connected profile. The heartbeat should end the connection within its lease. Disconnect / logout from app and notification; ensure no lingering VPN notification or tunnel.
11. Measure performance with all 135 servers. Each full scan consumes 135 Edge Function requests. Do not market the TCP values as download speed or guarantee geographic accuracy from provider labels.

The supplied source has not yet been visually rendered by Android, compiled to a verified APK, or tested for real VPN traffic in this authoring environment. The green map confirms a successful startup internet test, not continuous proof of privacy or security.
