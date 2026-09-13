# Yay VPN 1.2 — Android UI update

For the new UI and APK build steps, start with **README.md**. Your existing Supabase backend does not need redeployment for this update.

# Yay VPN — Supabase edition

Start with **GUIDE.md**. It walks through every step from creating a free project to building and testing the Android APK.

This is a Supabase migration of the original Linux/Docker package. The original ZIP cannot be uploaded unchanged to Supabase.

Included:

- Android app source, configured for a Supabase Edge Function URL.
- PostgreSQL migration with private tables and server-only RPC permissions.
- Edge Function for admin/user login, device limits, expiry, encrypted server profiles and connection leases.
- Local browser admin panel that manages cloud data without separate website hosting.
- Local secret generator, deployment helper, single-file dashboard deployment alternative.
- GitHub Actions workflows for backend checks and Android APK builds.

Validation: 14 JavaScript security/API tests and 8 PostgreSQL WASM behavior/permission checks passed. PostgreSQL WASM uses a single backend, so real concurrent-connection testing is provided as a separate CI gate. Android compilation, cloud deployment, and live VPN traffic have not been verified here. No APK is included.

No real credentials are included. Generate private secrets on your own computer. Never publish `.yay-secrets.env`.
