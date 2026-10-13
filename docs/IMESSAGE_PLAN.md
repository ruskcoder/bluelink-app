# iMessage for the Compose Messages app — implementation plan

Bringing OpenBubbles' iMessage stack into `com.ruskcoder.messages`: real Apple ID login, real
send/receive, replies, tapbacks, edits, unsends, typing, receipts, attachments and groups.

## Status

Phases 0–5 are built and the app compiles and packages end to end. What is **not** yet verified is
the part that needs hardware nobody in this repo has: no Apple account has actually been signed in,
because that needs an activation code from a Mac running OpenBubbles. Everything up to that door is
in place; whether Apple opens it is untested.

| Phase | State |
| --- | --- |
| 0 — Rust core builds for Android | Done. `cargo ndk` → `jniLibs`, wired into Gradle. |
| 1 — UniFFI surface | Done. `rust/imessage-core/src/kotlin_api/`. |
| 2 — Kotlin service layer | Done. `app/.../imessage/`. |
| 3 — Room migration v1→v2 | Done. |
| 4 — Setup and login UI | Done, untested against Apple. |
| 5 — Feature wiring | Done: text, replies, tapbacks, edits, unsends, typing, receipts, groups, and attachment upload. Downloading a received attachment is not wired. |
| 6 — Background and notifications | Foreground service, per-chat notifications, reconnect loop. Boot restart not wired. |

## 1. What we are building on

OpenBubbles is a Flutter app, but almost none of its iMessage value lives in Dart. It lives in two
Rust crates:

| Crate | What it is |
| --- | --- |
| `rustpush` | The protocol implementation. APS (Apple Push Service) socket, IDS registration and identity, per-device key negotiation, encryption, and the full message model. |
| `rust_lib_bluebubbles` | A wrapper that orchestrates `rustpush` into a usable service: `try_auth` -> `do_login` -> `register_ids` -> `setup_push` -> `SharedPushState` -> `recv_wait`/`send`. |

The Dart layer above them is presentation and storage. That is the layer we are replacing with
Compose — so the task is re-hosting the Rust, not porting protocol logic.

## 2. Architecture decision: UniFFI

The wrapper crate already speaks **two** FFI dialects:

- `src/api/api.rs` — `flutter_rust_bridge`, Dart-only, useless to Kotlin.
- `src/native.rs` and `src/keystore.rs` — **UniFFI**, already generating checked-in Kotlin at
  `android/app/src/main/kotlin/uniffi/rust_lib_bluebubbles/rust_lib_bluebubbles.kt`.

`native.rs` is already a Kotlin-facing layer that calls into `api.rs` functions. So the approach is
not novel plumbing — it is extending a pattern the upstream project already ships and tests. That
decides it:

> **Add an iMessage-focused `#[uniffi::export]` surface to the vendored wrapper crate, build it for
> Android with `cargo-ndk`, generate Kotlin bindings, and consume them from Compose.**

Rejected alternatives: embedding Flutter as a library (absurd for a Compose app); hand-written JNI
(strictly more work than UniFFI, no upside); running OpenBubbles as a background service and doing
IPC (fragile, and still needs the login UI).

One notable win over how OpenBubbles does it: `uniffi` is already declared with the `tokio` feature,
so `#[uniffi::export(async_runtime = "tokio")]` gives us **Kotlin `suspend` functions directly**.
OpenBubbles had to hand-roll callback traits (`MsgReceiver`, `CarrierHandler`, ...) because
`flutter_rust_bridge` owned the async runtime. We don't inherit that constraint.

## 3. Repository layout

```
messages/
  rust/
    rustpush/         vendored — protocol crate (+ its own path deps:
                      apple-private-apis/, open-absinthe/, cloudkit-proto/, keystore/)
    imessage-core/    vendored wrapper, renamed from rust_lib_bluebubbles
      src/api/api.rs      existing frb layer — kept, not called from Kotlin
      src/native.rs       existing uniffi layer (keystore, file packaging)
      src/kotlin_api.rs   NEW — our iMessage surface
    uniffi-bindgen/   host-only codegen bin
  app/src/main/jniLibs/<abi>/libimessage_core.so
  app/src/main/java/uniffi/imessage_core/imessage_core.kt   generated
```

`flutter_rust_bridge` stays as a dependency. `frb_generated.rs` has zero references to the crate
name and compiles cleanly; stripping it would mean removing `#[frb(...)]` attributes from ~2,800
lines of `api.rs` for no functional gain. It is dead weight, not a hazard, and can be removed later.

## 4. Phases

### Phase 0 — Rust core builds for Android

- [x] Vendor `rustpush/` and the wrapper crate; drop submodule pointers.
- [x] Rename crate `rust_lib_bluebubbles` -> `imessage_core`; pin the same `uniffi` git rev.
- [x] Add `[profile.release] strip = "debuginfo"` — an unstripped build is ~700 MB.
- [x] `cargo ndk` cross-compile, driven by the `cargoBuildRust` Gradle task.
- [x] arm64-v8a and x86_64 by default; override with `-PrustAbis=arm64-v8a` while iterating.
- [x] `generateUniffiBindings` task, with explicit dependencies from `compile*`, `ksp*` and
      `*JniLib*` tasks — AGP 9 refuses `Provider`s in the source set API, so those cannot be
      inferred.
- [x] `implementation("net.java.dev.jna:jna:5.12.0@aar")` — UniFFI's Kotlin runtime.

Two things cost real time to discover and are worth not rediscovering:

* **`strip = "symbols"` silently breaks binding generation.** uniffi's library mode reads the
  exported interface out of `.symtab`, which that setting removes — leaving `uniffi-bindgen` to exit
  0 having written nothing. `strip = "debuginfo"` drops the same bulk and keeps the table.
* **`--library` is a flag, not an option.** The library path is the positional `SOURCE` argument and
  has to come last, or bindgen reads the wrong thing and, again, writes nothing.

The toolchain is already proven on this machine: Rust 1.97, all four Android targets, cargo-ndk
4.1.2, NDK 28.2.13676358, protoc 35, and a previously built `librust_lib_bluebubbles.so`.

### Phase 1 — The UniFFI surface (`kotlin_api.rs`)

Deliberately **not** a re-export of `rustpush`'s types. Those are large, protocol-shaped, and would
drag plist/protobuf semantics into the UI layer. Instead a flat, app-shaped surface:

**Hardware identity**

```rust
fn config_from_activation_payload(data: Vec<u8>) -> Result<Arc<HwConfig>, ImError>
fn config_from_validation_data(data: Vec<u8>, extra: HwExtra) -> Result<Arc<HwConfig>, ImError>
async fn save_hw_config(dir: String, config: Arc<HwConfig>) -> Result<(), ImError>
fn load_hw_config(dir: String) -> Option<Arc<HwConfig>>
fn saved_apple_id(dir: String) -> Option<String>
fn is_registered(dir: String) -> bool
```

**Session and login** — an object, because login is a stateful multi-step negotiation:

```rust
async fn connect(dir: String) -> Result<Arc<Session>, ImError>

impl Session {
    async fn login(&self, apple_id: String, password: String) -> Result<LoginStep, ImError>
    async fn login_saved(&self)                     -> Result<LoginStep, ImError>
    async fn choose_sms_target(&self, target_id: u32) -> Result<LoginStep, ImError>
    async fn submit_code(&self, code: String)       -> Result<LoginStep, ImError>
    async fn register(&self)                        -> Result<Vec<String>, ImError>
    async fn handles(&self)                         -> Result<Vec<String>, ImError>
    async fn validate_targets(&self, targets: Vec<String>, sender: String) -> Result<Vec<String>, ImError>
    async fn reregister(&self)                      -> Result<(), ImError>
}

enum LoginStep {
    NeedsDeviceCode,
    NeedsSmsChoice { targets: Vec<PhoneTarget> },
    NeedsSmsCode,
    NeedsExtraStep { detail: String },
    LoggedIn,
}
```

`LoginStep` is a flattening of `icloud_auth`'s `LoginState`. That enum describes Apple's protocol
states, several of which are ours to resolve without asking the user anything — re-authenticating
with saved credentials, requesting a code, auto-selecting a sole trusted number. Those are driven to
completion inside `Session::advance`, so what reaches Kotlin is only ever a state the user has to
act on. That is also why there is one `submit_code` rather than two: the device flow reads its
answer off the push socket and the SMS flow posts it to Apple, but which applies is knowable from
state the session already holds.

**Messaging**

```rust
impl Session {
    async fn send_text(&self, msg: OutgoingText)    -> Result<String, ImError>  // returns guid
    async fn send_tapback(&self, t: OutgoingTapback) -> Result<String, ImError>
    async fn edit_message(&self, chat: ChatTarget, guid: String, part: u64, text: String) -> Result<String, ImError>
    async fn unsend_message(&self, chat: ChatTarget, guid: String, part: u64) -> Result<String, ImError>
    async fn set_typing(&self, chat: ChatTarget, active: bool) -> Result<String, ImError>
    async fn mark_read(&self, chat: ChatTarget, guid: String)   -> Result<(), ImError>
    async fn rename_group(&self, chat: ChatTarget, name: String) -> Result<String, ImError>
    async fn set_participants(&self, chat: ChatTarget, handles: Vec<String>, version: u64) -> Result<String, ImError>

    /// Suspends until the next push arrives. Kotlin wraps this in a `flow { }` loop.
    async fn next_event(&self) -> Option<IncomingEvent>
}
```

Using an async `next_event()` rather than a foreign callback trait (OpenBubbles' `MsgReceiver`)
keeps backpressure natural and gives Kotlin an ordinary `Flow` with no callback bridging.

`IncomingEvent` covers: `Message`, `Reaction`, `Edit`, `Unsend`, `Typing`, `Delivered`, `Read`,
`SendConfirm`, `GroupRename`, `GroupParticipants`, `GroupIcon`, `Error`.

### Phase 2 — Kotlin service layer

- `imessage/IMessageService.kt` — owns the `Session`, exposes `StateFlow<ConnectionState>` and a
  `Flow<IncomingEvent>`; wired into `AppContainer` alongside the existing repositories.
- `imessage/IMessageSyncer.kt` — consumes the event flow and writes through `ChatRepository`, so
  every existing screen keeps rendering from Room and needs no knowledge of Apple.
- `imessage/PushService.kt` — a foreground `Service` holding the APS socket, with reconnect and
  periodic re-registration.
- `imessage/AndroidNativeKeystore.kt` — ported from OpenBubbles verbatim.

That last one is not optional and is easy to miss. `rustpush` stores the IDS identity wrapped by a
key from the platform keystore (`IDSNGMIdentity::save`/`restore` go through `AesKeystoreKey`), and
on Android `rustpush` does *not* install one itself — the desktop path calls `init_keystore` under
`#[cfg(not(target_os = "android"))]`, and Android is expected to supply an implementation from
Kotlin via `setup_keystore`. Without it the first call that touches an identity panics, which is
most of setup. The Android Keystore's rules about which key types, digests and paddings a given
device actually supports are learned the hard way, so this is a port rather than a rewrite.

The existing UI does not talk to Rust. It talks to Room, exactly as it does today.

### Phase 3 — Data model and Room migration (v1 -> v2)

The current schema is local-first and cannot express iMessage semantics:

| Today | Problem | Change |
| --- | --- | --- |
| `MessageEntity.reactions: String` (JSON `List<String>`) | Tapbacks are per-sender, per-part, and toggle on/off. A flat emoji list loses who reacted and cannot be un-reacted. | New `reactions` table keyed `(message_guid, part, sender)` with `type` + `enabled`. |
| `ReplyPreview(id, name, text)` | iMessage replies address `reply_guid` **and** `reply_part`. | Add `reply_part`; keep the denormalized preview for rendering. |
| `ChatEntity.id: String` (local) | Chats are identified by participant set + optional group id. | Add `participants` (JSON), `group_id`, `service`. |
| No sender identity | Messages arrive from handles (`mailto:`/`tel:`). | Add `handle` to messages, map to contacts on read. |
| No error state | Sends fail per-recipient. | Add `error_code`/`error_text`. |

`DeliveryStatus` (`SENT`/`DELIVERED`/`READ`) already matches iMessage's model and stays.

### Phase 4 — Setup and login UI

A new `setup` nav graph shown when no registration exists, mirroring OpenBubbles' flow:

1. **Hardware identity** — QR scan or pasted code (spec in section 5).
2. **Apple ID** — email + password, or resume a saved account.
3. **2FA** — trusted-device code, with a fallback to SMS to a chosen trusted number.
4. **Handles** — pick which addresses to send from.
5. **Registration** — `register_ids`, then land in the chat list.

### Phase 5 — Feature wiring

Composer -> `send_text`; `BubbleMenu`/`ReactionBar` -> `send_reaction`; `FocusedReply` -> reply
targets; long-press edit/unsend -> `edit`/`unsend`; scroll-to-bottom -> `mark_read`; typing indicator
both directions; attachments through MMCS upload/download with progress.

### Phase 6 — Background and notifications

Foreground service notification, per-chat message notifications with inline reply, reconnect on
network change, re-registration before identity expiry.

## 5. Hardware identity — the QR flow

Apple will not accept IDS registration without validation data derived from genuine Apple hardware.
We replicate OpenBubbles' acquisition flow exactly, including its input formats.

**Producer** (a Mac running OpenBubbles, or OpenBubbles desktop) builds
`"OABS" || share_flag(1 byte) || protobuf(HwInfo)` and either renders it directly as a QR, or
AES-encrypts it, uploads it, and shows a short activation code.

**Consumer** (our scanner) dispatches on shape, matching OpenBubbles' `checkCode`:

| Input | Detection | Handling |
| --- | --- | --- |
| `https://hw.openbubbles.app/code/<CODE>` | URL prefix | strip prefix, then short-code flow |
| `MBxxxx-xxxx-xxxx-xxxx` | 21 chars, first segment 6, 3 dashes | short-code flow |
| base64 -> starts `OABS` | magic bytes | strip 5-byte header -> `config_from_encoded` |
| base64 -> 517 bytes, `[0] == 0x02` | length + tag | raw Mac validation data -> `config_from_validation_data` |

Every path above lands on a `MacOSConfig` holding the Mac's identifiers, persisted to
`hw_info.plist`. `MacOSConfig::generate_validation_data` then computes validation data from them
locally, against Apple's cert bag — so acquiring the identity is a **one-time** step and the Mac is
never contacted again.

**That computation is not in this repository.** It lives in `open-absinthe`, whose public form is a
stub: `ValidationCtx::new`, `key_establishment`, and `sign` all return
`AbsintheError::unavailable()`, and `HardwareConfig::from_validation_data` does too. So every
`MacOSConfig` path is a dead end in a build made from these sources — it fails at the first
`login_apple_delegates`, which is the first thing that asks for validation data. The same is true of
OpenBubbles built from its own sources; the shipped app is built against the real crate. Nothing
here substitutes: `omnisette`'s remote anisette v3 serves anisette headers, not validation data.

So the relay is also offered, contrary to the earlier decision recorded here. `RelayConfig` POSTs to
a registration relay for validation data on *every* registration, which does mean the device behind
it has to stay online — but it needs no OpenAbsinthe, and it is the only path that works in a source
build. `config_from_relay` is exposed to Kotlin as `config_from_relay(code, host, token)`, and a
bare `xxxx-xxxx-xxxx-xxxx` code is routed to Beeper's public relay, matching how OpenBubbles' own
`checkCode` dispatches on shape.

OpenBubbles additionally converts a *Mac* relay into a local `MacOSConfig` — it pulls raw validation
data once via `get-validation-data` and keeps the identifiers. That branch is not replicated, since
it lands straight back on the missing NAC implementation; a Mac relay is kept as a relay here.

**Short-code flow**: `GET https://hw.openbubbles.app/code/<sha256_hex(code)>` with header
`X-OpenBubbles-Get:`; the response's `data` field is a CryptoJS-format AES blob decrypted with the
code itself as passphrase, yielding the `OABS` payload.

**CryptoJS format**: `base64( "Salted__" || salt[8] || AES-256-CBC-PKCS7(plaintext) )`, with key and
IV derived by OpenSSL's `EVP_BytesToKey` using MD5 and no iteration — repeatedly
`md5(previous || passphrase || salt)` until at least 48 bytes, then key = `[0,32)`, IV = `[32,48)`.

This is ~40 lines of `javax.crypto` + `MessageDigest`, so it goes in **Kotlin** rather than Rust; it
adds no dependency on either side.

## 6. Risks

| Risk | Mitigation |
| --- | --- |
| APK size — the stripped `.so` is ~36 MB per ABI, and the debug APK ships it unstripped at ~56 MB. | `strip = "debuginfo"`, plus AGP stripping on release; build one ABI while iterating with `-PrustAbis=arm64-v8a`. |
| Build time — ~600 crates including vendored OpenSSL. | Cargo caching; the Gradle task only reruns when `rust/**` changes. |
| Apple protocol drift breaking registration. | Vendored `rustpush` is a moving target upstream; pin, and keep the vendoring a clean copy so rebasing stays possible. |
| Hardware identity expiry / relay availability. | Persist the config; surface a clear re-scan path rather than a silent failure. |
| UniFFI async across the JNI boundary. | The `tokio` feature is already declared upstream; validate with one async call end to end before building out the surface. |

## 7. Out of scope (this pass)

FaceTime, FindMy, iCloud keychain/passwords, shared streams, and SMS relay through a paired iPhone.
`rustpush`'s `facetime.rs` and `findmy.rs` alone are ~150k lines and none of it is needed for the
messaging surface the app renders today.
