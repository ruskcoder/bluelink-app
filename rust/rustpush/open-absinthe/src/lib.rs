use std::{error::Error, fmt::Display};

pub mod nac;

// The real backend. Closed source: it is compiled from a `private/` checkout at the repository
// root when the `emulator` feature is on, and absent otherwise.
#[cfg(feature = "emulator")]
#[path = "../../../../private/open-absinthe/src/emulator.rs"]
mod emulator;

#[derive(Debug)]
pub struct AbsintheError(String);

impl AbsintheError {
    /// The one genuine stub: parsing hardware identity out of a raw Mac validation-data blob is not
    /// implemented (the QR / activation-code path constructs the identity directly and never needs
    /// it). Distinct from an emulator *failure*, which carries a real diagnostic via [`Self::emu`].
    pub(crate) fn unavailable() -> Self {
        Self(concat!(
            "extracting hardware identity from a raw Mac validation-data blob is not implemented ",
            "in this build; use the QR / activation-code setup path, which supplies the identity ",
            "directly.",
        ).to_string())
    }

    /// What every validation call answers in a build without the `emulator` feature.
    #[cfg(not(feature = "emulator"))]
    pub(crate) fn no_backend() -> Self {
        Self(concat!(
            "Mac validation is unavailable in this source build; register with a validation ",
            "relay code instead.",
        ).to_string())
    }

    /// A failure from inside the emulated validation run. `context` should say what was being
    /// attempted so an on-device failure (where Apple only ever answers a flat "no") is diagnosable
    /// from the log rather than opaque.
    #[cfg(feature = "emulator")]
    pub(crate) fn emu(context: impl Into<String>) -> Self {
        Self(format!("emulated Mac validation failed: {}", context.into()))
    }
}

impl Display for AbsintheError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(&self.0)
    }
}

impl Error for AbsintheError { }
