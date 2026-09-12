# Security Policy

## Supported versions

Beam is in active development and has not shipped a stable release yet.

| Version | Supported |
| ------- | ---------- |
| `main`  | Yes        |
| Stable releases | None exist yet - the first release will be supported from publication |

## Reporting a vulnerability

We take security bugs seriously and appreciate responsible disclosure.

**Please do not report security vulnerabilities through public GitHub issues.**

Report them privately instead, via either channel:

1. **GitHub Private Vulnerability Reporting** - go to the
   [Security tab](https://github.com/greenbugx/Beam/security/advisories)
   of the repository and click *Report a vulnerability*. This is the
   preferred channel.
2. **Email** — if private reporting is unavailable, email
   **greenbugx@proton.me** with details of the issue.

Include as much of the following as you can:

- The type of issue (e.g., authentication bypass, path traversal, malformed
  payload handling)
- Full steps or a proof of concept for reproducing the issue
- affected component (protocol, discovery, transfer, storage)
- Android versions / devices where you observed it, if relevant
- Any potential impact you have identified

You can expect an acknowledgment within 72 hours. If confirmed, we will
work on a fix, coordinate disclosure with you, and credit the report in the
advisory unless you prefer to remain anonymous.

## Scope

Beam's attack surface is deliberately small. What is security-relevant:

- **The Beam transfer protocol** - all message parsing between devices
  (file offers, metadata, chunk data). Malformed or malicious payloads from
  a peer within a Beam room are in scope.
- **Room authentication/joining** - preventing unauthorized devices from
  silently joining a room.
- **File writing** - received files must never escape the intended storage
  location (path traversal, filename sanitization, MIME/size spoofing).
- **Runtime permissions** - the app must not request or use permissions
  beyond what the current feature requires.
- **Third-party dependencies** - known CVEs in dependencies used by a
  shipped release.

What is out of scope:

- Attacks requiring physical access to an unlocked device
- Social engineering, phishing, or intercepting shares made in public places
  by design (Beam rooms are open by intent - anyone nearby with the code can
  join)
- Vulnerabilities in Google Play Services / the Nearby Connections API
  itself (report those to Google)
- Denial of service via radio-layer interference (jamming Wi-Fi/Bluetooth)
- Unreleased, in-development features that are clearly marked incomplete in
  [TODO.md](TODO.md)

## Current security posture

Honest status, so you know what to expect:

- Transfers happen **device-to-device over the local network** - no cloud
  servers, no accounts, no telemetry, no analytics. Beam collects nothing
  and sends nothing anywhere except between the devices you connect.
- Transfers are **not yet encrypted or authenticated** beyond the transport
  provided by Nearby Connections. Room-level authentication, key exchange,
  and transfer encryption are planned as a dedicated milestone
  (M12 — Security in [TODO.md](TODO.md)). Treat current builds as
  experimental and do not use them to share sensitive material in
  untrusted environments.
- File transfer itself is not implemented yet - today only small text
  payloads are exchanged. Once file saving lands, its hardening
  (filename sanitization, path confinement, size validation) is
  security-critical and will be listed here.
