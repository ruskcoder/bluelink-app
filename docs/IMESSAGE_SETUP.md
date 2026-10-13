# Getting connected to iMessage

## What you need first

Apple will not register a client that cannot produce validation data derived from genuine Apple
hardware. There is no way around this in software — it is the whole reason OpenBubbles asks for a
Mac, and this app inherits the same requirement. So before anything else you need **an activation
code from a Mac**.

The app accepts every format OpenBubbles does, and tells them apart by shape, so you do not have to
say which kind you have:

| What you have | What to do |
| --- | --- |
| A Mac running OpenBubbles | On the Mac: **Settings → Device → Share Activation Code**. Scan the QR, or paste the code. |
| Another phone already set up with OpenBubbles | Same screen on that device — it can pass its hardware identity on. |
| Raw validation data from a Mac | Paste it base64-encoded. |
| A registration relay code (`xxxx-xxxx-xxxx-xxxx`) | Paste it. |

**An activation code is read once.** What gets stored on the phone is the Mac's identifiers — serial,
board id, ROM, MLB, platform UUID. Validation data is then computed locally from those on every
registration, talking to Apple rather than to the Mac. After setup the Mac never has to be reachable
again; it can be switched off or sold.

> **Activation codes need OpenAbsinthe.** Computing validation data from stored identifiers is the
> one step this repository cannot perform: `rustpush/open-absinthe` is a public placeholder with no
> implementation, so a build made from these sources accepts an activation code, shows you the Mac,
> and then fails at sign-in with *"Mac validation is unavailable in this source build"*. A relay code
> is the way through on such a build. See `IMESSAGE_PLAN.md` section 5.

**A relay code is used every time.** It points at an agent running on someone's Mac or iPhone that
produces validation data on request, so nothing is computed on the phone and OpenAbsinthe is not
involved. The cost is that the relay has to be reachable whenever this device registers. A bare
`xxxx-xxxx-xxxx-xxxx` code is dialled against Beeper's public registration relay.

A code of the form `MBxxxx-xxxx-xxxx-xxxx`, or a `https://hw.openbubbles.app/code/...` link, is
fetched from OpenBubbles' hardware service and decrypted with the code itself — the code never
reaches the server in the clear.

## Signing in

1. Launch the app. With no registration it opens on **Connect a Mac**.
2. Scan or paste the activation or relay code. The next screen shows the device it will identify as
   — check the serial before continuing.
3. Enter the Apple Account you use for iMessage.
4. Enter the verification code, from your trusted devices or by SMS. Apple decides which; the app
   follows.
5. It registers with IDS and drops you into the chat list.

You can skip setup and use the app locally. **Settings → Account → iMessage** starts it again later.

## Building

```bash
./gradlew assembleDebug
```

**The Rust core is not rebuilt by default.** Once `app/src/main/jniLibs/<abi>/libimessage_core.so`
exists, ordinary builds skip straight past it — a full cross-compile is ~600 crates including
vendored OpenSSL and runs well over ten minutes, which has no business inside every Kotlin build.
Gradle's up-to-date checks alone are not enough here: `openssl-sys` declares `rerun-if-env-changed`
on its compiler variables, so merely building from a different shell can send cargo back to the
start.

After changing anything under `rust/`, ask for it explicitly:

```bash
./gradlew assembleDebug -PbuildRust
```

A missing library still forces a build regardless, since an APK without one cannot run. While
iterating, narrow to a single ABI:

```bash
./gradlew assembleDebug -PbuildRust -PrustAbis=arm64-v8a
```

Requirements: the Rust toolchain with the Android targets installed, `cargo-ndk`, NDK
28.2.13676358, and `protoc` on `PATH`.

### A note on Perl, on Windows

Vendored OpenSSL configures itself with Perl, and refuses Strawberry Perl — it reports as `MSWin32`
and emits backslash paths, so `Configure` stops with *"this perl implementation doesn't produce Unix
like paths"*. Git for Windows ships an MSYS Perl that works. Which one wins depends on the PATH of
whatever launched the build, so a Git Bash shell would succeed where Android Studio failed.

The build now pins `OPENSSL_SRC_PERL` to Git's Perl itself, so it no longer matters where the build
is started from. If you have no Git for Windows install, set the variable by hand to any Perl that
reports something other than `MSWin32` for `$^O`.

```bash
rustup target add aarch64-linux-android x86_64-linux-android
```

```bash
cargo install cargo-ndk
```

## What works, and what does not

Working: sign-in with two-factor, sending and receiving, replies, tapbacks, edits, unsends, typing
indicators both ways, delivery and read receipts, group renames and membership changes, sending
photos, videos and files, and notifications.

A dropped pin or a contact card has no file behind it, so over iMessage those are sent as text —
a map link, or a name and number. That is a lesser rendering than iMessage's own card, and it beats
storing them locally where the recipient would get nothing.

Not wired yet:

* **Downloading incoming attachments.** They arrive with their name, size and type and render as a
  file bubble, but the bytes are not fetched — `download_attachment` exists on the Rust side and
  has no caller yet.
* **SMS relay** through a paired iPhone, and everything else outside messaging (FaceTime, FindMy,
  iCloud keychain).
* **Restart on boot.** The push service restarts if the system kills it, but not after a reboot
  until the app is opened.

One thing to know about conversation identity: iMessage has no server-side chat id — a conversation
*is* its participant set — so conversations created through iMessage are keyed by handle, while
chats created before sign-in are keyed by address-book id. A contact you had a local chat with will
appear as a second conversation once they message you over iMessage.
