# Android 1.4.1 connection recovery

This update fixes client-side cancellation and status handling. It does not establish that an upstream VLESS server or the cloud API is reachable from every phone/network.

## Changes

- Enhanced connection scans for up to 12 seconds and uses the best completed measurements. Manual country scans have a 60-second budget. Unfinished work is cancelled; a scan need not test every server before connecting.
- Lightning still selects one random server in the chosen country without a scan. It still requires a valid cloud grant and a successful VPN internet check.
- Connection startup has a 45-second deadline, separate from the scan budget. Cancel invalidates startup immediately, cancels registered HTTP requests, interrupts the startup task, and releases the Java TUN descriptor. Native core cleanup remains serialized before another connection is allowed.
- Late startup results cannot change a cancelled connection to Connected or retain a newly created TUN descriptor.
- A VPN-bound HTTPS check runs every 15 seconds after connection. A failed check displays No internet and removes the green map. If no check succeeds for 45 seconds, the VPN stops. A successful check confirms that test endpoint was reachable, not that every website works.
- Access lease expiry is still enforced independently. No backend migration or redeployment is needed.
- Disconnecting has its own UI status. Failure reasons appear on the main screen. The installed version appears in Settings.

## Validation

The cancellation tests cover late request registration, pending resources, completed requests, failing cleanup and 100 registration/cancellation races. Test bodies were executed locally on Java 17; changed Java files passed syntax parsing. Full Android compilation, lint and JUnit execution require the Android GitHub workflow. Native device behavior has not been verified locally.

## Build and phone checks

1. Start a new **Build Yay VPN test APK** workflow on **main**. Do not rerun an older run, which retains its old commit.
2. Download the new APK after the run succeeds. Settings should show **1.4.1-test**.
3. Use Android's Force stop for a stuck old app. Avoid Clear storage; that removes the saved session and can consume another device slot after signing in again.
4. Install the new build and test Lightning, then Enhanced. While it is connecting, tap the power button to cancel and confirm the VPN key disappears before trying again.
5. After connection, open a website. Turn off both Wi-Fi and mobile data and verify the app stops claiming healthy internet and eventually disconnects. Restore a network and reconnect.
6. If it still fails, capture the version, exact displayed error, selected country, and whether the failure happens on Wi-Fi, mobile data, or both. Do not include passwords or VLESS credentials.

Test APKs must have a matching signing certificate to update an existing installation. A signing mismatch is a packaging issue, not a reason to clear storage. Release distribution should use a stable signing key.
