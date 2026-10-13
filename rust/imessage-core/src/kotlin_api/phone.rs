//! Registering the SIM's phone number with iMessage, alongside the Apple ID.
//!
//! Apple proves a number the way an iPhone's CommCenter does: the device texts
//! `REG-REQ?v=3;t=<push token>;r=<nonce>;` to its carrier's Apple gateway, and Apple texts back
//! `REG-RESP?v=3;r=<nonce>;n=<number>;s=<signature>`. The number and signature authenticate a phone
//! [`IDSUser`], which is then registered on this device next to the email one. The SMS half lives in
//! Kotlin, since only the platform can send and receive texts; everything else is here.
//!
//! Turning it off is the same registration with the phone user left out. IDS registers a device's
//! handles as one set, so the number stops resolving to this device, and iPhones fall back to SMS
//! for it — and to the email for iMessage.

use openssl::x509::X509;
use std::sync::Arc;

use rustpush::{get_gateways_for_mccmnc, IDSUser, IDSUserType, IMClient};

use super::{session::Session, ChatTarget, ImError, Result};
use crate::api::api;

/// Answers the carrier's EAP-AKA challenge with the SIM — Android's `getIccAuthentication`.
#[uniffi::export(with_foreign)]
pub trait SimChallengeResponder: Send + Sync + std::fmt::Debug {
    /// The response to one challenge, or null if the SIM would not answer.
    fn respond(&self, challenge: String) -> Option<String>;
}

fn is_phone(user: &IDSUser) -> bool {
    matches!(user.user_type, IDSUserType::Phone)
}

fn decode_hex(hex: &str) -> Result<Vec<u8>> {
    let hex = hex.trim();
    if hex.len() % 2 != 0 {
        return Err(ImError::msg("Apple's reply carried a malformed signature"));
    }
    (0..hex.len())
        .step_by(2)
        .map(|i| u8::from_str_radix(&hex[i..i + 2], 16))
        .collect::<std::result::Result<Vec<u8>, _>>()
        .map_err(|_| ImError::msg("Apple's reply carried a malformed signature"))
}

/// Seconds until a user's authentication certificate lapses. Past that, IDS refuses to register
/// the user at all — and refuses the *whole* registration, email included.
fn auth_valid_for_s(user: &IDSUser) -> Option<i64> {
    let cert = X509::from_der(&user.auth_keypair.cert).ok()?;
    let now = openssl::asn1::Asn1Time::days_from_now(0).ok()?;
    let diff = now.diff(cert.not_after()).ok()?;
    Some(diff.days as i64 * 86_400 + diff.secs as i64)
}

impl Session {
    /// Swap the registered users and re-register, putting the old set back if Apple refuses.
    ///
    /// The live identity is updated in place rather than a new client built: the client is what
    /// the inbox and every send are already holding, and its re-registration schedule has to
    /// follow the new set or it would put the old one back at the next heartbeat.
    async fn swap_users(&self, client: &Arc<IMClient>, users: Vec<IDSUser>) -> Result<Vec<String>> {
        let previous = client.identity.users.read().await.clone();
        if let Err(err) = client.identity.update_users(users).await {
            // A refused registration leaves nothing registered; the old set still is, as far as
            // Apple knows, so re-assert it rather than leave the account dark.
            let _ = client.identity.update_users(previous).await;
            return Err(err.into());
        }
        let registered = client.identity.users.read().await.clone();
        api::save_users(&registered, self.dir.clone());
        Ok(api::get_handles(client).await?)
    }

    /// Put `phone` in place of whatever number was registered, next to the Apple ID.
    async fn add_phone_user(&self, client: &Arc<IMClient>, phone: IDSUser) -> Result<Vec<String>> {
        let mut users: Vec<IDSUser> = client
            .identity
            .users
            .read()
            .await
            .iter()
            .filter(|u| !is_phone(u))
            .cloned()
            .collect();
        users.push(phone);
        self.swap_users(client, users).await
    }

    /// A conversation that replies from a handle this device no longer holds — the phone number,
    /// after it was switched off — is moved onto one it does. Otherwise every send in it would go
    /// out under an address Apple no longer routes to us.
    pub(crate) async fn resolve_sender(&self, client: &IMClient, mut chat: ChatTarget) -> ChatTarget {
        let handles = client.identity.get_handles().await;
        if handles.is_empty() || handles.contains(&chat.sender) {
            return chat;
        }
        let replacement = handles
            .iter()
            .find(|h| chat.participants.contains(h))
            .unwrap_or(&handles[0])
            .clone();
        for participant in chat.participants.iter_mut() {
            if *participant == chat.sender {
                *participant = replacement.clone();
            }
        }
        let mut seen = std::collections::HashSet::new();
        chat.participants.retain(|p| seen.insert(p.clone()));
        chat.sender = replacement;
        chat
    }
}

#[uniffi::export(async_runtime = "tokio")]
impl Session {
    /// The number to text `REG-REQ` to, for the SIM whose network is `mccmnc` (e.g. `310260`).
    ///
    /// Read from Apple's own carrier bundles, the same ones an iPhone downloads.
    pub async fn phone_gateway(&self, mccmnc: String) -> Result<String> {
        let config = get_gateways_for_mccmnc(&mccmnc).await.map_err(|err| match err {
            rustpush::PushError::CarrierNotFound => {
                ImError::msg("Your carrier doesn't support registering a number with iMessage")
            }
            other => other.into(),
        })?;
        Ok(config.gateway)
    }

    /// This device's push token in upper-case hex — the `t=` of the `REG-REQ` text.
    pub async fn push_token_hex(&self) -> String {
        self.conn
            .get_token()
            .await
            .iter()
            .map(|b| format!("{b:02X}"))
            .collect()
    }

    /// The phone numbers currently registered on this device, as `tel:` handles.
    pub async fn phone_handles(&self) -> Result<Vec<String>> {
        let users = match self.client.lock().await.clone() {
            Some(client) => client.identity.users.read().await.clone(),
            None => api::restore_users(self.dir.clone()).unwrap_or_default(),
        };
        Ok(users
            .iter()
            .filter(|u| is_phone(u))
            .flat_map(|u| {
                u.registration
                    .get("com.apple.madrid")
                    .map(|r| r.handles.clone())
                    .unwrap_or_default()
            })
            .collect())
    }

    /// Seconds until the registered number has to be proved again by SMS, or null when no number
    /// is registered. Negative once it has lapsed.
    pub async fn phone_renew_in_secs(&self) -> Option<i64> {
        let users = match self.client.lock().await.clone() {
            Some(client) => client.identity.users.read().await.clone(),
            None => api::restore_users(self.dir.clone()).unwrap_or_default(),
        };
        users.iter().filter(|u| is_phone(u)).filter_map(auth_valid_for_s).min()
    }

    /// Register the number Apple vouched for in its `REG-RESP`, next to the Apple ID.
    ///
    /// `number` and `signature_hex` are the reply's `n=` and `s=`. Replaces any number already
    /// registered, which is also how a renewal is done. Returns every handle now registered.
    pub async fn enable_phone(&self, number: String, signature_hex: String) -> Result<Vec<String>> {
        let client = self.require_client().await?;
        let signature = decode_hex(&signature_hex)?;
        let phone = api::auth_phone(&self.conn, &self.config, number, signature).await?;
        self.add_phone_user(&client, phone).await
    }

    /// Register the number without SMS, by having the SIM answer the carrier's EAP-AKA challenge.
    ///
    /// The route an iPhone takes on carriers that support it, and the one that works where the
    /// `REG-REQ` text is dropped. `subscriber` is the SIM's EAP identity
    /// (`0<IMSI>@nai.epc.mnc<MNC>.mcc<MCC>.3gppnetwork.org`); reading it, and running the challenge,
    /// both need the `USE_ICC_AUTH_WITH_DEVICE_IDENTIFIER` app-op.
    pub async fn enable_phone_via_sim(
        &self,
        mccmnc: String,
        subscriber: String,
        imei: String,
        responder: Arc<dyn SimChallengeResponder>,
    ) -> Result<Vec<String>> {
        let client = self.require_client().await?;
        let phone = api::get_entitlements(&self.config, &self.conn, mccmnc, subscriber, imei, move |challenge| {
            let responder = responder.clone();
            Box::pin(async move {
                // Blocking: the SIM is reached over binder, and the answer takes a moment.
                tokio::task::spawn_blocking(move || responder.respond(challenge))
                    .await
                    .ok()
                    .flatten()
                    .unwrap_or_default()
            }) as flutter_rust_bridge::DartFnFuture<String>
        })
        .await
        .map_err(|err| {
            let text = format!("{err:#}");
            if text.contains("ICC auth") {
                ImError::msg("Your carrier doesn't support verifying your number through the SIM")
            } else {
                ImError::from(err)
            }
        })?;
        self.add_phone_user(&client, phone).await
    }

    /// Take the number off iMessage, keeping the Apple ID registered. Returns the handles left.
    pub async fn disable_phone(&self) -> Result<Vec<String>> {
        let client = self.require_client().await?;
        let users: Vec<IDSUser> = client
            .identity
            .users
            .read()
            .await
            .iter()
            .filter(|u| !is_phone(u))
            .cloned()
            .collect();
        if users.is_empty() {
            return Err(ImError::msg("No Apple ID is registered to fall back to"));
        }
        self.swap_users(&client, users).await
    }
}
