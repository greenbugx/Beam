<div align="center">
    <img src="app/src/main/res/drawable/beam_logo.png" height=128px width=auto>
    <h1>BEAM</h1>
    <p><em>"Beam it."</em></p>
    <h5><em>Send anything. Nearby. Instantly</em></h5>
</div>

<p align="center">
    <a href="https://kotlinlang.org"><img src="https://img.shields.io/badge/Kotlin-2.3.21-7F52FF" alt="Kotlin"></a>
    <a href="https://developer.android.com/jetpack/compose"><img src="https://img.shields.io/badge/UI-Jetpack%20Compose-4285F4" alt="Jetpack Compose"></a>
    <a href="https://developers.google.com/nearby/connections/overview"><img src="https://img.shields.io/badge/Transport-Nearby%20Connections-34A853" alt="Nearby Connections"></a>
    <img src="https://img.shields.io/badge/Android-8.0%2B-3DDC84" alt="Android 8.0+">
    <a href="LICENSE"><img src="https://img.shields.io/badge/License-AGPL--3.0-red" alt="AGPL-3.0"></a>
</p>

> [!WARNING]
> Beam is on active development stage and currently it's unusable.
---

Beam is a peer-to-peer sharing app for Android. One device starts a beam and shows a short code, the other enters it, and they're connected without any internet or accounts or cables.

Under the hood it uses Google's Nearby Connections API, which automatically picks the best available transport (Bluetooth, BLE, or Wi-Fi) without any manual setup.

## Highlights

- **Nearby discovery** -> find other Beam devices around you in real time
- **Short code pairing** -> hosts share a 6-character code, joiners type it in and connect automatically
- **No internet needed** -> everything happens directly between devices
- **Modern UI** -> fully built with Jetpack Compose and a custom in-house design system

## Tech Stack

| Layer | Technology |
| --- | --- |
| Language | Kotlin |
| UI | Jetpack Compose |
| Networking | Google Play Services Nearby Connections |
| Transfer protocol | BEAM/1.1, custom app-level protocol -> see [PROTOCOL.md](PROTOCOL.md) |
| Architecture | MVVM with ViewModel |
| Linting | ktlint (CLI) |

## Getting Started

### Prerequisites

- Android Studio (latest stable recommended)
- JDK 17 or newer
- An Android device running Android 8.0 (API 26) or newer
- Google Play Services on the device (required for Nearby Connections)

> [!NOTE]
> Nearby Connections does not work on emulators - you need two physical devices to test discovery and transfer.

### Build

```bash
git clone https://github.com/greenbugx/Beam.git
cd Beam
./gradlew assembleDebug
```

The APK will be available at `app/build/outputs/apk/debug/`.

To install directly on a connected device:

```bash
./gradlew installDebug
```

## Project Structure

```
app/src/main/java/com/beam/app/
├── MainActivity.kt          # Entry point and screen routing
├── network/                 # Nearby Connections wrapper
├── permissions/             # Runtime permission handling
├── protocol/                # BEAM/1.1 protocol: wire codec, session, file transfer
├── session/                 # Session state, ViewModel, and models
├── ui/
│   ├── components/          # Reusable UI components (buttons, etc.)
│   ├── files/               # Beam workspace (shared files, transfers, peers)
│   ├── home/                # Home screen (create or join)
│   ├── join/                # Code entry screen for joining
│   ├── nearby/              # Nearby beam browsing and its code entry
│   ├── room/                # Active session screen (code display, beams list, peers)
│   └── theme/               # Design system (colors, typography)
└── util/                    # Beam code generation and validation
```

## How It Works

1. **Create** -> one device starts a beam and gets a short code
2. **Join** -> the other device enters the code, or taps a discovered beam under nearby beams and enters its code (QR coming later)
3. **Connect** -> the joiner's device finds the beam advertising that code and connects automatically
4. **Send** -> files are negotiated and transferred in verified chunks by the app-level protocol (file transfer is in active development, see [PROTOCOL.md](PROTOCOL.md))

## Roadmap

Beam is built milestone by milestone — see [TODO.md](TODO.md) for the full plan:

- [x] **M1 — Foundation**: native app, custom design system, home screen, create/join flows, Beam codes
- [x] **M2 — Sessions & Connectivity**: discovery, advertising, connection handling, peer tracking
- [ ] **M3 — Single File Transfer**: SAF picker, transfer protocol ([BEAM/1.1](PROTOCOL.md)), integrity verification
- [ ] **M4+ — Large files, multi-user rooms, resumable transfers, QR joining, and more**

## Documentation

- [PROTOCOL.md](PROTOCOL.md) -> the authoritative BEAM/1.1 protocol design: sessions, messages, chunking, integrity and security.
- [TODO.md](TODO.md) -> milestone plan

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for setup, code style (ktlint), commit conventions, and the PR checklist.

## Security

Found a vulnerability? Please report it privately - see [SECURITY.md](SECURITY.md).

## License

This project is licensed under the [GNU AGPL-3.0](LICENSE)
