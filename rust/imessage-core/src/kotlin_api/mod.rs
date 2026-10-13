//! The Kotlin-facing iMessage surface.
//!
//! `api::api` is the flutter_rust_bridge layer and is Dart-shaped: it hands out `rustpush` types
//! directly and leans on frb's opaque-pointer support to do it. This module is the equivalent for
//! Compose, built on UniFFI — the same way [`crate::native`] and [`crate::keystore`] already expose
//! the Android-native pieces upstream.
//!
//! It deliberately does *not* re-export `rustpush`'s model. Those types are protocol-shaped (plist
//! dictionaries, protobuf records, `NSAttributedString` archives) and pushing them across the FFI
//! would drag that vocabulary all the way into the UI layer. Everything here flattens into records
//! the app can store and render as-is.

mod backup;
mod hardware;
mod messaging;
mod phone;
mod session;

pub use backup::*;
pub use hardware::*;
pub use messaging::*;
pub use session::*;

/// Everything crossing the boundary fails as one variant carrying a message.
///
/// Kotlin has no use for a structured taxonomy here: the app either shows the string to the user
/// (login and registration failures are worded by Apple, and that wording is the useful part) or
/// logs it. The exceptions are the two variants the setup flow branches on, which need to route the
/// user somewhere rather than show a dead end.
#[derive(uniffi::Error, Debug)]
pub enum ImError {
    Failed { reason: String },
    /// No hardware identity is configured — send the user back to the scanner.
    NeedsHardware,
    /// Signed in, but IDS registration has not completed — send the user back to setup.
    NotRegistered,
}

impl std::fmt::Display for ImError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Failed { reason } => write!(f, "{reason}"),
            Self::NeedsHardware => write!(f, "No hardware identity is configured"),
            Self::NotRegistered => write!(f, "Not registered with iMessage"),
        }
    }
}

impl ImError {
    pub(crate) fn msg(message: impl Into<String>) -> Self {
        Self::Failed { reason: message.into() }
    }
}

impl From<anyhow::Error> for ImError {
    fn from(value: anyhow::Error) -> Self {
        // `{:#}` walks the cause chain, which is where anyhow puts the part worth reading.
        Self::Failed { reason: format!("{value:#}") }
    }
}

impl From<rustpush::PushError> for ImError {
    fn from(value: rustpush::PushError) -> Self {
        Self::Failed { reason: format!("{value}") }
    }
}

impl From<std::io::Error> for ImError {
    fn from(value: std::io::Error) -> Self {
        Self::Failed { reason: format!("{value}") }
    }
}

pub(crate) type Result<T> = std::result::Result<T, ImError>;
