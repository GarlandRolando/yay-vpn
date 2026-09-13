# Yay VPN 1.4 — Android and Windows

- Choose Lightning or Enhanced below the power button. The choice is remembered on the device; disconnect before changing it.
- Lightning picks exactly one random profile from the selected country without scanning first. A failed server is reported; tap again to pick another. It still obtains an access grant and verifies the tunnel before showing Connected.
- Enhanced retains the measured TCP ranking, randomizes close latency ties, and tries up to three ranked candidates. This is a reachability measurement, not a guarantee of bandwidth or fastest application response.
- Live download/upload counters appear below Time remaining and above Refresh locations. They use the sing-box per-core traffic stream. They are not a measurement of all device interfaces or a speed test.
- VPN latency is an HTTPS URL test through the selected proxy, refreshed about every 15 seconds. Country-list TCP pings remain separate. Missing/stale samples display a dash, never a fabricated ping or frozen last rate.
- Telemetry uses an ephemeral loopback port and a new secret per tunnel. Only 127.0.0.1 permits local HTTP on Android; cloud traffic remains HTTPS. No controller secrets or raw connection lists are saved.
- Stopping a tunnel stops its monitors and clears the readings. Monitoring runs separately from access-renewal checks.

## Validation

Windows client cross-publish and the standalone selection/telemetry checks run locally. Android Java syntax and XML were checked locally; the full Android compile, unit tests and lint run in the Android GitHub workflow. Native connection and device UI checks still need real Android/Windows devices.

Run new Android and Windows workflows from main to build the updated binaries. Re-running an old job uses its old source commit. Existing app downloads on the website do not update until the files are replaced and the website is redeployed. The backend schema/API does not need redeployment for this update.
