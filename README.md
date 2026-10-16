# BlueSync

An Android messaging app, written in Kotlin and Jetpack Compose, that works for iMessage.

The protocol work is [rustpush](https://github.com/OpenBubbles/rustpush), the Rust implementation
behind OpenBubbles. BlueSync re-hosts it behind a UniFFI surface (`rust/imessage-core`) and builds a
native Compose client on top: conversations, replies, tapbacks, edits, unsends, typing indicators,
receipts, groups, attachments, and iMessage games through an OpenPigeon extension host.

This is an independent project. It is not affiliated with, endorsed by, or supported by Apple, and
it can stop working whenever Apple changes its services.

## What this repository does not contain

Apple only registers a client that presents validation data derived from genuine Apple hardware.
The component that computes that data on the phone, from a Mac's identifiers scanned once, is
**closed source and not in this repository**. `rust/rustpush/open-absinthe` is its public interface
with a stub behind it.

A build made from these sources therefore compiles, runs, and does everything else, but cannot
validate by itself: it registers through a **validation relay code** instead, which points at an
agent on a Mac or iPhone that produces validation data on request. See
[docs/IMESSAGE_SETUP.md](docs/IMESSAGE_SETUP.md).

## Linking with a QR code

Install the BlueBubbles desktop app on a Mac, open **Settings → Device → Share Activation Code**,
then scan the QR code from BlueSync's setup screen. The code is read once; afterwards the Mac does
not need to stay on.

## Building

```bash
git clone --recurse-submodules https://github.com/ruskcoder/bluesync-app
```

```bash
./gradlew assembleDebug
```

The first build cross-compiles the Rust core, which takes a while. It needs the Rust toolchain with
the Android targets, `cargo-ndk`, NDK 28.2.13676358, and `protoc` on `PATH`; the details, including
a note for Windows, are in [docs/IMESSAGE_SETUP.md](docs/IMESSAGE_SETUP.md). After changing anything
under `rust/`, rebuild it explicitly:

```bash
./gradlew assembleDebug -PbuildRust
```

## Layout

| Path | What it is |
| --- | --- |
| `app/` | The Android app. |
| `rust/imessage-core/` | The UniFFI layer the app calls into. |
| `rust/rustpush/` | Vendored rustpush. |
| `rust/rustpush/open-absinthe/` | The validation interface, stubbed. |
| `docs/` | Setup guide and the implementation plan. |

## Credits

BlueSync stands on the work of the [BlueBubbles](https://bluebubbles.app) team and community, whose
tooling makes iMessage on Android possible, including the desktop Mac app used to link BlueSync.
Thanks also to [OpenBubbles](https://openbubbles.app) and rustpush for the protocol implementation.

## Contributing

Run this once after cloning, so the pre-commit guard is active:

```bash
git config core.hooksPath .githooks
```

## License

BlueSync links rustpush, which is licensed under the Server Side Public License v1, so this
repository is distributed under the same license: see [LICENSE](LICENSE). Third-party components
keep their own terms; see [NOTICE.md](NOTICE.md), `rust/rustpush/LICENSE`, and
`rust/vendor/unicorn-engine-sys/COPYING`.
