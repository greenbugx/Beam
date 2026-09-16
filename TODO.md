# Beam — Milestones

## M1 — Foundation ✅

- [x] Native Android project
- [x] Kotlin + Jetpack Compose
- [x] Android SDK 36
- [x] Custom Beam design system
- [x] Obsidian + Electric Lime theme
- [x] Home screen
- [x] Create Beam flow
- [x] Join Beam flow
- [x] 6-character Beam codes
- [x] Ktlint setup

---

## M2 — Beam Sessions & Connectivity ✅

- [x] Nearby Connections integration
- [x] `P2P_CLUSTER` strategy
- [x] Start advertising
- [x] Start discovery
- [x] Discover nearby Beams
- [x] Display discovered Beams
- [x] Manually select a Beam
- [x] Connect to a Beam
- [x] Connection state handling
- [x] Disconnect handling
- [x] Connection failure handling
- [x] Track connected peers
- [x] Basic payload sending
- [x] Basic payload receiving
- [x] Verify `HELLO FROM BEAM` transfer on physical devices

---

## M3 — Single File Transfer 🚧

### Files UI

- [x] Create `BeamFilesScreen`
- [x] Show connected devices
- [x] Add `+ ADD FILES` button
- [x] Show selected files
- [x] Show incoming transfers
- [x] Show outgoing transfers
- [x] Show transfer progress

### File Selection

- [x] Android Storage Access Framework picker
- [x] Select a file
- [x] Read file name
- [x] Read MIME type
- [x] Read file size
- [x] Store/access selected `Uri`

### Wire Codec

- [x] Frame codec (length-prefixed frames, per-type payload limits)
- [x] Chunk header codec (fixed 28-byte big-endian header)
- [x] Control envelope codec (JSON, Section 11)
- [x] Malformed-frame classification (truncated / oversize / unknown type / invalid)
- [x] Protocol unit test suite

### Session Layer

- [x] Transport abstraction (`Transport`: send frame / receive `Flow` of frames)
- [x] Typed session messages: `SESSION_HELLO`, `SESSION_READY`, `SESSION_CLOSE`
- [x] Handshake state machine (per-link, both roles)
- [x] Version negotiation (`supportedVersions`, major/minor rules, refuse on major mismatch)
- [x] Capability exchange + intersection (`CHUNKING` baseline, `MULTI_TRANSFER` optional)
- [x] Per-link `mid` counter + duplicate detection
- [x] Malformed-frame link policy (discard first, `SESSION_CLOSE` on second)
- [x] Session close: graceful (`SESSION_CLOSE`) + abrupt (link lost)

### Offer & Metadata

- [x] File metadata model (`fileId`, `transferId`, `name`, `mime`, `sizeBytes`, `sha256`, `chunkSize`, `chunkCount`)
- [x] `transferId` / `fileId` generation per the identity model
- [x] Validation gate (metadata validated before any allocation or state change)
- [x] Filename sanitization (collision-safe, never a path)
- [x] `FILE_OFFER` send / receive
- [x] `FILE_ACCEPT` / `FILE_REJECT` (with reject reasons)

### Chunk Streaming

- [x] Transfer state machine as an exhaustive sealed hierarchy (`OFFERED` → `TRANSFERRING` → `VERIFYING` → `COMPLETED`, terminal states)
- [x] Chunking at 256 KiB default (last chunk may be short)
- [x] Streaming file I/O (never a whole file in RAM, sender or receiver)
- [x] Flow control window (bounded sender memory: window × chunkSize)
- [x] Cumulative range ACKs (`CHUNK_ACK` every N chunks)
- [x] `TRANSFER_START` / `CHUNK_DATA` / `CHUNK_ACK` / `TRANSFER_END`
- [x] Offset-addressed temp writes (`.part` pre-sized to `sizeBytes`)

### Integrity & Completion

- [x] SHA-256 streamed over the assembled temp file (one pass, no copy)
- [x] `TRANSFER_VERIFIED` / `VERIFY_FAILED` (hash mismatch → delete temp → FAILED)
- [x] Atomic publish to final name before `TRANSFER_VERIFIED`
- [x] `.ranges` sidecar (record during transfer, delete on completion/cancel — M3 never resumes)
- [x] Temp cleanup on all terminal states + stale-temp sweep on app restart

### Errors, Cancel & Timeouts

- [x] `TRANSFER_CANCEL` from either side (second cancel is a no-op)
- [x] `TRANSFER_ERROR` + the stable error vocabulary (enums + short details, never stack traces)
- [x] Timeout contracts as configurable policy objects
- [x] Duplicate `FILE_OFFER` → cached decision re-sent, user not re-prompted
- [x] Duplicate `CHUNK_DATA` → idempotent write

### Protocol Test Suite

- [x] Handshake happy path + version negotiation matrix
- [x] Offer/accept and offer/reject flows (no data after reject)
- [x] Full transfer: text file → `VERIFIED` → `COMPLETED` both sides
- [x] Edge sizes: empty file, 1 byte, exactly one chunk, chunkSize, chunkSize + 1
- [x] Cancel at each state; connection loss at each state
- [x] Hash mismatch (flip one byte) → `VERIFY_FAILED`, temp deleted, session alive
- [x] Duplicate offer/chunk idempotency
- [x] Malformed frames → link policy

### Protocol Wiring

- [x] `LinkTransferWire`: `TransferWire` over `LinkSession` (CTRL envelopes + DATA frames)
- [x] `TransferLink` per peer: routes session events into the transfer layer, tracks remote identity + negotiated capabilities
- [x] `TransferManager`: user actions in (offer/accept/reject/cancel), one active transfer per link (`FILE_REJECT{BUSY}` otherwise)
- [x] Offer decision via `OfferDecisionCache` (duplicate offer re-sends the cached decision, no re-prompt)
- [x] Validation gate before prompting + filename sanitization
- [x] Aggregated per-transfer state `Flow` for the UI (derived progress/speed/ETA, injectable `Clock`)
- [x] `FileSource` interface: streaming read of the source, never a whole-file read
- [x] Streamed SHA-256 for offer preparation
- [x] Offer timeout on both sides (60 s → `FILE_REJECT{EXPIRED}` / `EXPIRED`)
- [x] accept→start timer (10 s → `TRANSFER_ERROR{TRANSFER_TIMEOUT}` → FAILED)
- [x] Link loss → `PAUSED`, then `FAILED` when the reconnect window expires; receiver temp survives the window
- [x] `TempSweep` on manager init
- [x] [Section 41](PROTOCOL.md#41-testing-strategy) suite repointed at production code + the two missing-state tests
- [ ] [Section 40](PROTOCOL.md#40-logging) structured logging (events + redaction rules) — protocol emits, app renders

#### M3.1 hardening remaining before transport integration

- [x] Link/manager shutdown: stop routing/sender jobs and progress ticker; close receiver resources; test close/detach cleanup
- [x] Incoming pending-offer cancel: remove the offer, notify the peer, preserve terminal/idempotent behavior, and release the link
- [x] Wake paused sender waiters on cancellation so source streams close promptly
- [ ] Guard accept-to-start timer installation/expiry against START arriving while ACCEPT is being sent
- [ ] Receiver inactivity watchdog: reset on progress, pause on expiry, fail and clean up after the configured limit
- [ ] Review lifecycle serialization, cached rejection reasons, and terminal-message routing; add focused regression tests
- [ ] Reconcile [Section 18](PROTOCOL.md#18-transfer-state-machine) exhaustive transitions with [Section 31](PROTOCOL.md#31-timeouts) required accept-to-start failure without editing the locked protocol

### Nearby Transport Adapter

- [ ] `NearbyTransport`: `Transport` over one Nearby endpoint (one frame per payload)
- [ ] `Frame.encode()` out; each full payload handed to the frame codec in
- [ ] Malformed payload → `FrameMalformed` (link policy)
- [ ] `onDisconnected` → `LinkLost`; `close()` → disconnect
- [ ] Per-endpoint demux (one `Transport` per endpoint id)
- [ ] Byte-link seam so the adapter logic is JVM-testable - the GMS binding stays a thin shell
- [ ] Verify a 256 KiB + 28-byte BYTES payload on device

### App Wiring

- [ ] One `LinkSession` per connected endpoint
- [ ] Offer prompt: accept / reject with a reason
- [ ] Protocol state → `BeamTransfer` (real progress + speed; no dead UI fields)
- [ ] `.beam-tmp` temp dir in app storage
- [ ] Publish destination: app-scoped `Beam/` + collision-safe naming
- [ ] Retire [M2](#m2--beam-sessions--connectivity-) ad-hoc text payloads in favor of `SESSION_*` envelopes
- [ ] Cancel from both sides surfaced in the UI

### On-Device Verification

- [ ] Transfer a small text file
- [ ] Transfer an image
- [ ] Transfer a video
- [ ] Transfer a large file
- [ ] Calculate SHA-256
- [ ] Verify received file integrity

---

# M4 — Large File Transfer

- [ ] Buffered streaming
- [ ] Prevent loading entire files into RAM
- [ ] Chunk-based transfer
- [ ] Chunk size / buffer tuning
- [ ] Transfer progress calculation
- [ ] Transfer speed calculation
- [ ] ETA calculation
- [ ] Cancellation
- [ ] Failure recovery
- [ ] Test 1 GB+ files
- [ ] Test 5 GB+ files
- [ ] Sustained transfer testing

---

# M5 — Multi-User Beam

- [ ] Connect multiple devices to one Beam
- [ ] Display all connected users
- [ ] Send one file to multiple users
- [ ] Independent transfer state per user
- [ ] Simultaneous transfers
- [ ] Per-peer progress
- [ ] Per-peer transfer speed
- [ ] Handle users joining during transfers
- [ ] Handle users leaving during transfers
- [ ] Stress test multiple devices

---

# M6 — Multiple File Transfers

- [ ] Multi-file picker
- [ ] File queue
- [ ] Multiple concurrent transfers
- [ ] Queue management
- [ ] Cancel individual files
- [ ] Retry failed transfers
- [ ] Transfer history
- [ ] Better transfer UI

---

# M7 — Reliable Transfers

- [ ] Activate `RESUME` capability + `RESUME_REQUEST`
- [ ] Chunk IDs
- [ ] Chunk ordering
- [ ] Missing-chunk detection
- [ ] Partial-file storage
- [ ] Transfer checkpoints
- [ ] Resume interrupted transfers
- [ ] Reconnection handling
- [ ] Integrity verification
- [ ] Corrupted transfer detection

---

# M8 — Transfer Engine

- [ ] Dedicated transfer manager
- [ ] Transfer scheduler
- [ ] Bandwidth management
- [ ] Adaptive chunk sizing
- [ ] Concurrent transfer limits
- [ ] Backpressure
- [ ] Memory optimization
- [ ] Battery optimization
- [ ] Performance profiling

---

# M9 — Peer-Assisted Distribution

- [ ] `PEER_ASSIST` capability + `CHUNK_HAVE` / `CHUNK_REQUEST` / `PEER_SOURCE`
- [ ] Host → Peer A
- [ ] Host → Peer B
- [ ] Peer A → Peer B
- [ ] Detect useful peers
- [ ] Distribute chunks between peers
- [ ] Prevent duplicate chunks
- [ ] Verify distributed chunks
- [ ] Optimize total Beam throughput

---

# M10 — QR Joining

- [ ] Generate Beam QR code
- [ ] Display QR on host
- [ ] QR scanner
- [ ] Encode Beam connection information
- [ ] Scan → automatically join Beam
- [ ] Keep manual code entry as fallback
- [ ] Handle invalid QR codes
- [ ] Handle expired Beams

---

# M11 — PC Support

- [ ] Decide PC transport
- [ ] TCP/WS transport adapter speaking BEAM/1 frames
- [ ] Android → browser prototype
- [ ] Browser → Android prototype
- [ ] Local-network discovery
- [ ] PC file receiving
- [ ] PC file sending
- [ ] Transfer progress
- [ ] Large-file support
- [ ] Linux support
- [ ] Windows support
- [ ] macOS support

---

# M12 — Security

- [ ] `ENCRYPTION` capability + HELLO auth extension (HMAC proof / AEAD option)
- [ ] Secure Beam handshake
- [ ] Authenticate peers
- [ ] Prevent unauthorized joins
- [ ] Encrypt transfers
- [ ] Validate incoming metadata
- [ ] Validate file sizes
- [ ] Prevent path traversal
- [ ] Secure temporary files
- [ ] Beam session expiration
- [ ] Security review

---

# M13 — Performance & Reliability

- [ ] Test different Android devices
- [ ] Test different Wi-Fi chipsets
- [ ] Test weak connections
- [ ] Test screen-off behavior
- [ ] Test background behavior
- [ ] Test reconnects
- [ ] Test 1 → N transfers
- [ ] Benchmark throughput
- [ ] Reduce CPU usage
- [ ] Reduce memory usage
- [ ] Fix race conditions
- [ ] Soak testing

---

# M14 — Product Polish

- [ ] Connection animations
- [ ] Transfer animations
- [ ] Empty states
- [ ] Error states
- [ ] File thumbnails
- [ ] Notifications
- [ ] Android Share Sheet integration
- [ ] Settings
- [ ] Device name
- [ ] About Beam
- [x] App icon
- [ ] Branding cleanup
- [ ] Accessibility
- [ ] Final UI polish

---

# M15 — Release

- [ ] Release build
- [ ] Signing configuration
- [ ] R8 / ProGuard review
- [ ] Remove debug logging
- [ ] Crash handling
- [ ] Privacy policy
- [ ] Store assets
- [ ] Internal testing
- [ ] Closed testing
- [ ] Production release
- [ ] Open-source documentation
