# Windows 1.4.2: connection stability and Android parity

## Findings

The previous Windows client did not have Android 1.4.1's 45-second probe-based disconnection. It did force the cloud domain outside the VPN and stop on every heartbeat HTTP error. Startup also did not consistently recheck ownership after asynchronous work, allowing a late result to race with Stop.

## Changes

- Each connection attempt owns a cancellation token and all of its resources. Stop invalidates it before cancelling requests and closing the engine. A late engine is immediately disposed; a late success cannot revive the cancelled session or close a replacement session.
- Enhanced scanning is limited to 12 seconds and uses completed results; manual full-country scans have a 60-second budget. Lightning still picks one random server and skips scanning. Enhanced can try up to three measured candidates.
- The selected-country connection sequence has a 45-second budget after scanning. Stop also cancels startup directly, including pending HTTP requests and engine configuration writes.
- Startup checks use an authenticated loopback proxy belonging to the exact engine. They try Google and then Cloudflare HTTPS 204 endpoints; redirects do not count as success.
- Periodic checks are advisory. Failure shows VPN active / Internet check unavailable and removes the green health indication while leaving the tunnel running. It never triggers an automatic server switch or disconnect.
- Removed the cloud-domain direct-routing exception. Cloud traffic follows the VPN route after startup. API pooled sockets retire before the next heartbeat so physical-network connections are not reused indefinitely.
- HTTP 408, 425, 429 and 5xx heartbeat errors retry on the next heartbeat while the original lease remains valid. Rejected authorization/configuration and expired leases still stop the tunnel.
- Lease time uses a monotonic timer. The server already caps the lease at account expiry; the extra local wall-clock expiry check was removed to avoid premature disconnection after clock adjustments.
- The home screen shows disconnect reasons, and Settings shows version 1.4.2. Existing local credentials, language, mode and device identity are preserved.

## Verification

The .NET client compiled and published for Windows x64 from Linux. Automated checks cover country selection, telemetry, independent probe fallback, redirect rejection, cancelling blocked HTTP, temporary versus permanent access errors, late resource cleanup, replacement-session isolation and 100 startup/Stop races. The Windows-specific native process guard and actual TUN traffic still require a Windows machine. No on-device or visual runtime verification is claimed.

## Install and test

1. Close the old app. Extract the entire new ZIP into a new folder.
2. Open YayVPN.exe and accept the administrator prompt needed to create the VPN interface. Do not move the EXE out of its folder.
3. Confirm Settings shows 1.4.2. Saved credentials are read from the existing per-user YayVPN data directory.
4. Connect using Lightning, browse, and leave it running for at least five minutes. A probe warning alone should not disconnect it.
5. Disconnect while fully connected, then try Enhanced and press the power button while scanning or connecting. Verify the interface is released and another connection can start.
6. If it disconnects by itself, record the exact reason, country, mode and whether your Wi-Fi/network changed. Compare the same exact VLESS server in another client when isolating server versus client behavior.

The cloud backend is unchanged. The website download is unchanged until you replace its Windows ZIP and redeploy. For a native CI build, start a new **Build Yay VPN Windows test app** workflow on main.
