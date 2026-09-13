# Third-party components

Yay VPN is an independent client; it does not imply endorsement by v2rayNG, SagerNet, sing-box, or their maintainers. No v2rayNG code or assets are included.

The build retrieves sing-box 1.12.12 at commit `54ed58499d7063136ed52dabf87d179d252425d0` from https://github.com/SagerNet/sing-box and uses SagerNet gomobile 0.1.8. Preserve `sing-box-LICENSE.txt` and the corresponding source/build information for any distributed binary. The complete GNU GPL version 3 text is in the package root `LICENSE`.

Python runtime dependencies: cryptography (Apache-2.0 / BSD), Gunicorn (MIT). Deployment dependency: Caddy (Apache-2.0). The Android/Go dependency trees include their own license obligations; this notice is not a replacement for those licenses. Their binaries/sources are downloaded at build time and are not bundled in this source ZIP.
