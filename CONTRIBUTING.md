# Contributing to Beam

Thanks for your interest in contributing! Beam is a native Android app for
fast, local, peer-to-peer file sharing without any internet, accounts, or cloud.

# Let's Get You Started

## Prerequisites

- Android Studio (latest stable recommended)
- JDK 17 or newer
- An Android device running Android 8.0 (API 26) or newer (recommended Android 16 (API 36)), with Google Play
  Services -> Nearby Connections does not run on emulators
- [ktlint](https://ktlint.github.io/) 1.8.0 (CLI install, see below)

## Getting the code

```bash
git clone https://github.com/greenbugx/Beam.git
cd Beam
./gradlew assembleDebug
```

## ktlint setup (CLI)

This project lints with the standalone ktlint CLI, not a Gradle plugin, per
the [official CLI install docs](https://ktlint.github.io/ktlint/latest/install/cli/).
Style rules live in [`.editorconfig`](.editorconfig).

Install (any of the options below):

```bash
# Option 1: download the latest release
curl -sSLO https://github.com/pinterest/ktlint/releases/download/1.8.0/ktlint \
    && chmod a+x ktlint \
    && sudo mv ktlint /usr/local/bin/

# Option 2: Homebrew (macOS/Linux)
brew install ktlint # Homebrew on linux

# Option 3: sdkman (recommended)
sdk install ktlint 1.8.0
```

Check and format:

```bash
ktlint              # check only
ktlint -F           # check and auto-fix
```

All Kotlin code must pass `ktlint` with zero violations before it is
committed. Run `ktlint -F` before pushing.

## Code style

- `ktlint_official` code style, enforced via `.editorconfig`
  (`ktlint_code_style = ktlint_official`, max line length 120)
- Trailing commas are required where ktlint expects them
- Composables are named in lowerCamelCase like functions (`beamButton`,
  `homeScreen`), since they are functions, not classes
- Components are built from Compose primitives (`BasicText`, `Box`, `Row`,
  `Column`, `Canvas`, Compose Animation) and  **not** Material components. Beam
  has its own design system (`ui/theme`); do not add Material 3 dependencies
  or `MaterialTheme` wrappers. If you need a shared component, add it under
  `ui/components` following the `Beam*`/`beam*` naming convention
- UI stays decoupled from the transfer/networking layer, the transfer engine
  must never depend on Compose
- State flows one direction: action → ViewModel → state → UI. Use
  `StateFlow` for observable state

## Transfer protocol (BEAM/1)

The app-level transfer protocol is fully specified in [PROTOCOL.md](PROTOCOL.md).
Anything touching file transfer, session handling, or messaging must follow it:

- Read the relevant section of [PROTOCOL.md](PROTOCOL.md) before implementing a
  protocol behavior (message types, states, chunking, integrity, errors)
- Keep the protocol decoupled from the transport: protocol code must never
  depend on Nearby Connections, and UI must never depend on protocol internals
- If you change protocol behavior, update [PROTOCOL.md](PROTOCOL.md) in the same
  PR so the document stays authoritative
- Message names, fields, and states must match the spec exactly; do not invent
  new messages, IDs, or error codes on the fly

## Commit messages

This project follows [Conventional Commits](https://www.conventionalcommits.org/):

```text
<type>: <short imperative summary>

[optional body]
[optional footer]
```

Common types: `ci`, `feat`, `fix`, `docs`, `chore`, `refactor`, `test`, `style`, `network`.

## Project layout

```
app/src/main/java/com/beam/app/
├── MainActivity.kt          # Entry point and screen routing
├── network/                 # Nearby Connections wrapper
├── permissions/             # Runtime permission handling
├── session/                 # Session state, ViewModel, and models
├── ui/
│   ├── components/          # Reusable Beam components
│   ├── files/               # Beam workspace (shared files, transfers, peers)
│   ├── home/                # Home screen (create or join)
│   ├── join/                # Code entry screen for joining
│   ├── nearby/              # Nearby beam browsing and its code entry
│   ├── room/                # Active session screen
│   └── theme/               # Design system (colors, typography)
└── util/                    # Beam code generation and validation
```

## Testing on devices

Nearby Connections requires physical hardware - discovery, advertising, and
transfers cannot be verified on an emulator. For any change touching
networking or transfers, verify on at least two real devices:

```bash
./gradlew installDebug
adb logcat -s BeamNearby
```

The `BeamNearby` tag is the log channel for the entire networking layer.

## Pull requests

1. Fork the repo and create a branch from `main`
2. Make your change, keeping the scope focused and one logical change per PR
3. Run `ktlint -F` and fix anything it cannot autofix
4. Run `./gradlew assembleDebug` and make sure it builds
5. Test on real hardware if your change touches networking, permissions, or
   transfers
6. If your change touches the transfer protocol or session messaging, make sure
   it matches [PROTOCOL.md](PROTOCOL.md) and update the doc if behavior changed
7. Open a PR describing what changed and why, referencing the relevant
   issue if there is one

## Need help?

Open an issue with your question, or start a discussion. Keep in mind what
Beam is *not*: 
- no social features 
- no cloud storage
- no messaging. 

If your idea adds weight to the core sharing experience, it
probably belongs elsewhere.
