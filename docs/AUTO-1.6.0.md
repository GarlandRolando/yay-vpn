# Yay VPN 1.6.0: shared Auto engine

Android and Windows now use the same independently implemented `yay-auto`
monitor, compiled into pinned sing-box v1.12.12. This is inspired by the
observable behavior of Hiddify's balancer, not a claim of identical internals
or guaranteed connection time. Lightning and Enhanced retain their selection
behavior. Choose **Auto · all countries** to use the new monitor.

## What changed

- Ten concurrent native workers test real HTTP 204 responses through the
  authorized proxy outbounds, including the proxy/TLS handshake. Results become
  usable as each worker finishes; the app does not wait for the slowest result.
- A second independent test website starts after 500 ms if the first has not
  succeeded. Both share a five-second budget. Redirects, captive portals and
  non-204 replies are not considered successful tests.
- Recent latency results persist for up to 30 minutes in app-private storage.
  Fresh tests take priority over cached measurements. Config-derived SHA-256
  tags keep results attached to the same configuration when pool order changes.
  The history contains hashes, timestamps and latency, not raw credentials.
- The last working pool is tried first on reconnect, filtered against the current
  available server list. All configurations still require fresh signed cloud
  authorization; neither metadata nor latency history grants access.
- New connections rotate among measured routes within 100 ms of the best result.
  Existing streams stay on their original route; payload is never replayed across
  servers. TCP and UDP destinations only use compatible outbounds. HTTPS tests
  measure TCP reachability and cannot independently prove UDP reachability.
- A failed dial invalidates that node and permits one bounded retry on a different
  node. Failed nodes are prioritized in the next test cycle. Changes to the
  underlying network invalidate fresh measurements and request another cycle.
- Periodic checks run every 30 seconds; failure-triggered requests coalesce rather
  than spawning unlimited testing loops. Cancellation closes active probes and
  prevents results from reviving a stopped session.
- Pool authorization can run up to 12 existing signed requests concurrently,
  instead of four. Windows Auto authorization has a five-second request budget.
  This is not a batch API: the cloud can still be a startup bottleneck. Existing
  session, account-expiry, device-limit and heartbeat checks remain in place.
- Both clients hedge their final tunnel internet check too. The Connected label
  still requires an actual successful request through the tunnel.

## Limits that remain

Auto authorizes country-diverse pools of up to 12 nodes. It tries another pool if
startup cannot establish working access, within the existing overall two-minute
search budget. Once connected, native failover is within that active pool. It
does not add the other subscription nodes to a running engine dynamically; if
all active nodes stay unavailable, reconnect to search the remaining pools.

An established TCP stream cannot migrate between servers. Losing its node may
interrupt a download/call even when new requests can use another node. Real
networks, DNS, blocked test websites, cloud cold starts and the availability of
nodes still determine connection time. Neither five-second startup nor 100%
success is guaranteed. No extra Hiddify servers or protocols are introduced.

## Build and install

1. In GitHub Actions, run **Build Yay VPN test APK** on `main` and keep the existing
   API URL. Download the new artifact after a successful run and install its APK.
2. Run **Build Yay VPN Windows test app** on `main`. Extract the entire new ZIP,
   including its `engine` folder. Do not combine a new app with an old engine.
3. Check Settings shows `1.6.0` (Android test suffix is normal), choose Auto, and
   connect. Existing installations do not update merely because GitHub changes.
4. No Supabase SQL migration or Edge Function deployment is required.

Both workflows apply `scripts/apply-auto-engine.py` to verified upstream commit
`54ed58499d7063136ed52dabf87d179d252425d0`. The script accepts repeat application
and Windows line endings, but rejects unrelated tracked source changes.
Ordinary upstream URLTest remains available; only `yay-auto` uses this behavior.

## Verification performed

- Native monitor and group tests with the Go race detector: incremental selection,
  ten-worker limit, cancellation, stale-result rejection, private cache/expiry,
  near-best rotation, secondary test URL, network reset, bounded dial failover,
  and real HTTP status validation through a mock outbound.
- Linux native engine build and config validation; Windows native cross-build;
  libbox package compilation with the existing engine feature tags.
- Windows client compilation (zero warnings/errors) and existing client checks,
  including the new delayed-primary fallback and stable config identity cases.
- 20 Android JVM tests; Java syntax validation for changed app files.

A full Android APK build and actual Android/Windows TUN tests must be completed
on GitHub/device hardware. This development environment cannot validate the
native TUN/network-monitor lifecycle. The mock-node integration harness
`tests/auto-pool-engine.py` is retained for a host with those OS capabilities;
its `--check-only` mode validates production-generated engine configuration.
Before sharing broadly, test cold/warm connects, Stop during startup, Wi-Fi/mobile
switching, a failed node, all nodes unavailable, expired access and device removal.

## Source and licensing

The additions under `engine/overlay` are GPL-3.0-or-later and contain no copied
Hiddify source. The existing sing-box license and dependency notices apply.
The Windows bundle includes the unchanged upstream source archive plus the Yay
VPN source archive containing the overlay and apply/build scripts. Together they
provide the source and instructions for reproducing the modified native engine.
To reconstruct: extract/clone the pinned upstream source as a Git checkout,
apply `python scripts/apply-auto-engine.py ENGINE_DIRECTORY` from Yay VPN source,
then use the documented workflow's Go version, tags and linker flags.

Design references inspected: Hiddify's `hiddify-core/v2/config/builder.go` and
`hiddify-sing-box` commit `170d8315cab7a8695fd80469073ed2f1d07d63af`, specifically
`common/monitoring/outbound_monitoring.go` and `protocol/group/balancer`.

For offline reconstruction from the included source archives without Git metadata,
copy every file in `engine/overlay/` to its matching relative location in the
upstream archive, then add the following first statement inside
`RegisterURLTest` in `protocol/group/urltest.go`:

```go
outbound.Register[option.YayAutoOutboundOptions](registry, "yay-auto", NewYayAuto)
```

This is the sole change to an existing upstream Go file. Run the same tests/build
commands from the workflows in that reconstructed engine directory.
