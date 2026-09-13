# Android 1.4.2: avoid probe-triggered disconnects

## Confirmed problem

Version 1.4.1 deliberately stopped the VPN if a Google HTTPS probe had not succeeded for 45 seconds. Its exact message was "VPN internet access was lost. Try another country or network." That message identifies the local watchdog decision; it does not prove every tunnel connection had failed.

## Changes

- Removed the 45-second probe-driven disconnection. Probe failure is advisory: the UI says "VPN active" and "Internet check unavailable" while the tunnel stays running. Healthy checks restore the normal green state. This supersedes the health-stop behavior in ANDROID-1.4.1.md.
- Startup and periodic tunnel-bound checks try a Cloudflare HTTPS 204 endpoint if the Google endpoint fails. Redirects and non-204 responses are not accepted. Cancellation prevents fallback or late success.
- Once ON, cloud API requests use the established Yay VPN network. The core's upstream sockets still bypass the VPN. Startup authorization still needs the cloud API to be reachable before a Yay tunnel exists.
- Temporary heartbeat HTTP failures (408, 425, 429, 5xx) wait for the next scheduled renewal, as transport failures already did. They do not extend the granted lease. Expired leases and non-transient authorization/configuration errors still stop the VPN.
- Lightning remains one random server with no scan; a failed server can still fail to connect. Enhanced ranks measured candidates and can try up to three. No automatic server switching was added.

## Validation and limits

Five reachability test bodies were compiled and executed locally on Java 17: one-probe success, timeout with independent fallback, rejecting redirects, cancellation before fallback, and cancellation racing success. The existing cancellation checks were also executed. Changed Java sources passed syntax parsing. Android compile/lint and a real phone test remain required.

## Phone verification

Build a new Android workflow run on main and install the resulting APK. Settings must show 1.4.2-test. Test a website immediately after connecting, leave the app running for at least five minutes, and test again. If the probe warning appears while browsing still works, the VPN should remain active. If it disconnects, record the exact message, installed version, connection duration, mode and Wi-Fi/mobile-data state.

For a fair comparison with another client, use the same exact VLESS server, phone and network. Comparing different random servers cannot isolate an app bug. A successful probe is still only a reachability sample, not a guarantee that every destination works.
