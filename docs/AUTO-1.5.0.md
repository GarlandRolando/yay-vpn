# Yay VPN 1.5.0: Auto selection for Android and Windows

On the home screen, select **Auto · all countries**, then press the power button. Tap the power button again to cancel connection or disconnect. The mode is remembered on that device. Lightning and Enhanced retain their country-based selection.

Auto shuffles nodes within each country and takes one from each country before taking a second. It authorizes up to 12 nodes in a pool, with at most four simultaneous cloud requests. A large country therefore cannot monopolize the initial pool when there are at most 12 countries.

The existing pinned sing-box 1.12.12 engine receives a `urltest` group tagged `proxy`. Both traffic and remote DNS keep using `proxy`. The engine tests HTTPS through each real proxy, rather than relying on TCP port reachability. It rechecks the active pool every 30 seconds and applies a 100 ms tolerance to avoid unnecessary changes. Existing inbound connections are not forcibly interrupted when the selected node changes; a connection on a failed node may still need the application to retry it.

If a pool cannot establish verified internet access during startup, the client advances to the next pool. The search is cancellable and has a two-minute overall deadline. It stops searching further pools once a working pool connects. Thus Auto selects among its active pool; it does **not** claim to benchmark every server or guarantee the globally fastest server. If the entire active pool later becomes unusable, disconnect and press Connect to search other pools again. Individual node failure can be handled by URLTest within the active pool.

## What Hiddify does

Inspected official sources:
- https://github.com/hiddify/hiddify-core/blob/main/v2/config/builder.go
- https://github.com/hiddify/hiddify-sing-box/blob/170d8315cab7a8695fd80469073ed2f1d07d63af/protocol/group/balancer/lowest_delay.go
- https://github.com/hiddify/hiddify-sing-box/blob/170d8315cab7a8695fd80469073ed2f1d07d63af/protocol/group/balancer/roundrobin.go
- https://sing-box.sagernet.org/configuration/outbound/urltest/

Hiddify's inspected core builds distinct lowest-delay and configurable balance choices. Its custom sing-box fork implements those strategies. The round-robin implementation uses test history and an acceptable delay range to distribute new connections among eligible nodes. This is not a separate rescue network. It still needs working configured upstream servers.

Yay implements automatic low-delay selection using upstream URLTest. It does not copy Hiddify's fork-only `balancer` configuration, distribute every request round-robin, add WARP, or introduce new protocols. Current imported VLESS nodes retain their original settings.

## Access and operating limits

Every pool member needs its own signed cloud authorization and heartbeat using the existing endpoints. No backend deployment or database migration is required. Auto maintains the earliest outstanding member lease; temporary request failures do not extend access. A denied, changed or removed member stops the pool so a fresh connection can update authorization. Credentials stay in the existing in-memory engine configuration; no server keys are added to the public UI or diagnostics.

A full 12-node pool produces up to 12 heartbeat requests per 45-second cycle, compared with one for a single-server connection. Consider backend usage before making Auto the default for a large customer base.

A blocked cloud endpoint can prevent authorization before the VPN starts. A blocked test endpoint can affect which routes are judged usable. If every configured server is down, Auto cannot create connectivity. Neither low latency nor a successful test guarantees every website is accessible. Under-five-second startup is possible on responsive networks but is not guaranteed.

## Validation

- Windows .NET 8 client cross-build: passed, zero warnings/errors.
- Windows client checks: passed, including pool diversity, configuration isolation, DNS/routing, cancellation, telemetry and HTTP error classification.
- Android JVM checks: 18 passed, including new pool tests and existing selection/cancellation/probe tests. Edited Java sources also passed syntax parsing.
- Pinned sing-box 1.12.12 accepted the generated Auto configuration with `check`.
- Full native live-failover test was blocked by the local environment: `start service: operation not permitted`. It is not recorded as passed.
- Android APK build/lint, Android device operation and Windows native TUN operation still require CI/device verification.

For the local mock-proxy integration test, use:

```text
python tests/auto-pool-engine.py PATH_TO_SING_BOX PATH_TO_DOTNET
```

It generates a configuration through the production Windows AutoPool builder, tests selection between two local SOCKS proxies, disables the faster proxy, and checks that new traffic uses the remaining one. No real credentials or external test traffic are needed. Add `--check-only` to validate the generated configuration without starting a network service.

## Install

Start a new Android workflow and a new Windows workflow on `main`; do not rerun an old commit's job. Install the new Android APK using the same signing identity as your existing test app. For Windows, close Yay VPN and extract the entire new artifact to a fresh folder. Check version 1.5.0 and the new Auto button before sharing with users.
