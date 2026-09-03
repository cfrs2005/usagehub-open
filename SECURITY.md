# Security Policy

## Reporting a vulnerability

Do not open a public issue for a security vulnerability. Email the report to the contact shown on the UsageHub web site. Include the affected version, a minimal reproduction, impact, and any suggested mitigation.

Do not include access tokens, session cookies, private keys, user data, or live account details in the report.

## Client security model

- Collector ingest tokens are scoped to one user workspace and must be stored outside this repository.
- Android display tokens are encrypted using the Android Keystore on the device.
- HTTPS is required for cloud endpoints. Loopback HTTP is permitted only in automated tests.
- The server validates the timestamp, nonce, body hash, and HMAC signature for every ingest request.
- This repository must not contain server credentials, production exports, APK signing keys, or personal configuration.
