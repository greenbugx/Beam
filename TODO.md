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

# M3 — Single File Transfer 🚧

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

### File Protocol

- [ ] Define transfer message types
- [ ] Create file metadata model
- [ ] Generate transfer ID
- [ ] Send file offer
- [ ] Receive file offer
- [ ] Accept file
- [ ] Reject file
- [ ] Start file transfer
- [ ] Stream file bytes
- [ ] Save received file
- [ ] Cancel transfer
- [ ] Handle transfer errors

### Verification

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