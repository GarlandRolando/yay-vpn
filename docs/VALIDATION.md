# Validation and remaining gates

## Passed locally

- 14 JavaScript tests: password hashing, AES-GCM tamper detection, HKDF seed derivation, Android DER signature interoperability, signed-body/path validation, expired signatures, URI parser limits, CORS/origin checks, admin privilege separation, encrypted writes, and authorized connection-config delivery.
- The PostgreSQL migration executed in PGlite 0.5.8 (PostgreSQL WASM), including private schema, RLS, grants and stored function compilation.
- Eight SQL behavior/permission tests passed: anonymous permission denial, device-slot capacity, same-device session rotation, replay rejection, logout/removal behavior, password-reset race protection, expiry/revocation, server revisions, and app/admin separation (some checks share one test case).
- PGlite runs one backend. Multiple submitted login attempts are serialized there. This validates the SQL behavior but does not establish real concurrent-connection lock behavior. The included backend-checks.yml uses PostgreSQL 16 and independent psql connections for that gate.
- Admin JavaScript and Node helper syntax checks.

## Not established by these tests

- No project has been deployed to Supabase from this environment.
- No Supabase Edge runtime execution, gateway CORS/JWT behavior or cloud secrets configuration has been tested against a live project.
- No Android SDK/NDK/gomobile build, APK install, real protocol handshake, IP change, DNS/IPv6 leak test or UI rendering test has completed here.
- The Actions workflows are supplied but have not been run here.
- No security audit, production load test or guarantee of free-plan availability.

Use GUIDE.md checkpoints and PHONE-TESTS.md before treating the app as fully deployed. The original Python/SQLite backend's 14 tests do not establish correctness of this new implementation; this edition has its own tests.
