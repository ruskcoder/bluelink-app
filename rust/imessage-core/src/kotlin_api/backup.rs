//! Moving a working iMessage setup onto another device.
//!
//! The state directory cannot simply be copied. Nearly everything Apple-shaped in it — the IDS
//! identity, the NGM identity, the Grand Slam password — is sealed under keys that live in the
//! Android Keystore and are, by design, non-exportable. A byte-for-byte copy restores as a pile of
//! ciphertext nothing on the new phone can open.
//!
//! So a backup carries *credentials* rather than state, and the new device re-derives the rest:
//!
//! * the Mac hardware identity, re-encoded as the same `OABS` payload a shared activation QR
//!   carries — see [`super::HwConfig::to_activation_payload`], which is already what this uses;
//! * the Apple Account credential, decrypted here and re-sealed on the far side under that
//!   device's own key.
//!
//! With both in place the new device runs the ordinary flow — connect, `login_saved`, `register` —
//! and comes up with its own push token, its own NGM identity and its own keystore. Nothing
//! device-bound is cloned, which is also why the two installs do not fight over one registration.

use super::{ImError, Result};
use crate::api::api;

/// An Apple Account, in the only form that survives leaving the device it was signed in on.
///
/// [`hashed_password`](Self::hashed_password) is the SHA-256 that Grand Slam authenticates with,
/// not the password itself — but it is password-equivalent, so a backup holding it has to be
/// encrypted at rest. The Kotlin side does that before any of this reaches a file.
#[derive(uniffi::Record)]
pub struct AccountCredential {
    pub apple_id: String,
    pub hashed_password: Vec<u8>,
}

/// The saved Apple Account, decrypted, or `None` when nobody has ever signed in here.
#[uniffi::export]
pub fn export_account(dir: String) -> Option<AccountCredential> {
    let (apple_id, hashed_password) = api::read_gsa_account(dir)?;
    Some(AccountCredential { apple_id, hashed_password })
}

/// Adopt an Apple Account exported from another device.
///
/// Only writes the credential. Signing in with it — and the two-factor round trip Apple will ask
/// for, since this is hardware it has not seen before — is the ordinary login path afterwards.
#[uniffi::export]
pub fn import_account(dir: String, credential: AccountCredential) -> Result<()> {
    api::write_gsa_account(dir, credential.apple_id, &credential.hashed_password)
        .map_err(|e| ImError::msg(format!("{e:#}")))
}
