<div align="center">
    <img src="app/src/main/res/drawable/beam_logo.png" height=128px width=auto>
    <h1>BEAM</h1>
    <p><em>"Beam it."</em></p>
    <h5><em>Send anything. Nearby. Instantly.</em></h5>
</div>

<p align="center">
    <a href="https://kotlinlang.org"><img src="https://img.shields.io/badge/Kotlin-2.3.21-7F52FF" alt="Kotlin"></a>
    <a href="https://developer.android.com/jetpack/compose"><img src="https://img.shields.io/badge/UI-Jetpack%20Compose-4285F4" alt="Jetpack Compose"></a>
    <a href="https://developers.google.com/nearby/connections/overview"><img src="https://img.shields.io/badge/Transport-Nearby%20Connections-34A853" alt="Nearby Connections"></a>
    <img src="https://img.shields.io/badge/Android-8.0%2B-3DDC84" alt="Android 8.0+">
</p>

---

Beam is a peer-to-peer sharing app for Android. Discover nearby devices, connect instantly, and send anything between them without internet or pairing codes or cables.

Under the hood it uses Google's Nearby Connections API, which automatically picks the best available transport (Bluetooth, BLE, or Wi-Fi) without any manual setup.

## Highlights

- **Nearby discovery** -> find other Beam devices around you in real time
- **Instant connections** -> one tap to create or join a beam, no pairing required
- **No internet needed** -> everything happens directly between devices
- **Modern UI** -> fully built with Jetpack Compose and a custom in-house design system

## Tech Stack

| Layer | Technology |
| --- | --- |
| Language | Kotlin |
| UI | Jetpack Compose |
| Networking | Google Play Services Nearby Connections |
| Architecture | MVVM with ViewModel |
| Linting | ktlint |

## Getting Started

### Prerequisites

- Android Studio (latest stable recommended)
- An Android device running Android 8.0 (API 26) or newer
- Google Play Services on the device (required for Nearby Connections)

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
├── MainActivity.kt          # Entry point
├── network/                 # Nearby Connections wrapper
├── permissions/             # Runtime permission handling
├── session/                 # Beam session state and ViewModel
└── ui/
    ├── components/          # Reusable UI components
    ├── home/                # Home screen
    └── theme/               # Design system (colors, typography)
```

## How It Works

1. **Advertise** -> one device creates a beam and starts advertising itself
2. **Discover** -> nearby devices scan and find the advertised beam
3. **Connect** -> both sides authenticate and establish a connection
4. **Send** -> payloads are transferred directly between devices

## License

This project is licensed under [AGPL-3.0](LICENSE)