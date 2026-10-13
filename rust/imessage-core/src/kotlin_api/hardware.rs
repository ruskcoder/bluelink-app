//! Hardware identity: acquiring it, inspecting it, and persisting it.
//!
//! Apple will not accept IDS registration from a client that cannot produce validation data derived
//! from genuine Apple hardware, so this has to be read off a real Mac.
//!
//! That happens exactly once. What is stored is the machine's identifiers — serial, board id, ROM,
//! MLB, platform UUID — and `MacOSConfig::generate_validation_data` computes validation data from
//! them locally on every registration afterwards, talking to Apple rather than to the Mac. So the
//! Mac never has to be reachable again, and there is no live connection to maintain.
//!
//! See `docs/IMESSAGE_PLAN.md` section 5 for the acquisition flow and the payload formats.

use std::sync::Arc;

use super::{ImError, Result};
use crate::api::api::{self, HwExtra as ApiHwExtra, JoinedOSConfig};

/// An Apple hardware identity, opaque to Kotlin.
#[derive(uniffi::Object)]
pub struct HwConfig {
    pub(crate) inner: JoinedOSConfig,
}

/// Version metadata that has to accompany raw validation data, which carries none of its own.
#[derive(uniffi::Record)]
pub struct HwExtra {
    pub version: String,
    pub protocol_version: u32,
    pub device_id: String,
    pub icloud_ua: String,
    pub aoskit_version: String,
}

impl From<HwExtra> for ApiHwExtra {
    fn from(value: HwExtra) -> Self {
        ApiHwExtra {
            version: value.version,
            protocol_version: value.protocol_version,
            device_id: value.device_id,
            icloud_ua: value.icloud_ua,
            aoskit_version: value.aoskit_version,
        }
    }
}

/// What the setup UI shows once a code has been accepted, so the user can confirm it is their Mac.
#[derive(uniffi::Record)]
pub struct DeviceInfo {
    pub name: String,
    pub serial: String,
    pub os_version: String,
}

/// The whole hardware record, for the device page in settings.
///
/// Optional throughout below the first three because a relay identity has no machine of its own to
/// describe — it holds the address of an agent that vouches for one. A field it cannot answer is
/// `None`, so the page can say "not applicable" rather than print an empty line.
#[derive(uniffi::Record)]
pub struct DeviceDetails {
    pub name: String,
    pub serial: String,
    pub os_version: String,
    pub product_name: Option<String>,
    pub board_id: Option<String>,
    pub os_build: Option<String>,
    pub platform_uuid: Option<String>,
    pub root_disk_uuid: Option<String>,
    pub mlb: Option<String>,
    pub rom: Option<String>,
    pub mac_address: Option<String>,
    pub device_id: Option<String>,
    pub protocol_version: Option<u32>,
    pub icloud_user_agent: Option<String>,
    pub aoskit_version: Option<String>,
    /// Set only for a relay identity: the agent that answers for it.
    pub relay_host: Option<String>,
    /// Whether [`HwConfig::to_activation_payload`] can produce anything for this identity.
    pub exportable: bool,
}

/// The defaults OpenBubbles pairs with raw validation data.
///
/// Not arbitrary: they have to describe a machine Apple will accept, and they travel with the
/// validation blob as a matched set.
fn default_hw_extra() -> ApiHwExtra {
    ApiHwExtra {
        version: "13.6.4".to_string(),
        protocol_version: 1660,
        device_id: uuid::Uuid::new_v4().to_string(),
        icloud_ua: "com.apple.iCloudHelper/282 CFNetwork/1408.0.4 Darwin/22.5.0".to_string(),
        aoskit_version: "com.apple.AOSKit/282 (com.apple.accountsd/113)".to_string(),
    }
}

/// Parse whatever the QR scanner decoded, without the caller having to know the formats.
///
/// Two payloads arrive as raw bytes and are told apart by shape, matching OpenBubbles' `checkCode`:
///
/// * `"OABS" || share_flag || protobuf(HwInfo)` — a hardware identity exported by another
///   OpenBubbles install, which is what both the direct QR and the short activation code carry.
/// * a 517-byte blob tagged `0x02` — validation data straight off a Mac.
///
/// Format detection lives here rather than in Kotlin because it is a property of the payload, not
/// of the scanner. The short-code fetch and its decryption stay on the Kotlin side, because those
/// are network and crypto the platform already provides.
#[uniffi::export]
pub fn config_from_activation_payload(data: Vec<u8>) -> Result<Arc<HwConfig>> {
    if data.len() > 5 && &data[..4] == b"OABS" {
        // Byte 4 is the "may be shared" flag, which only governs the producer's own UI.
        let inner = api::config_from_encoded(data[5..].to_vec())?;
        return Ok(Arc::new(HwConfig { inner }));
    }

    if data.len() == 517 && data[0] == 0x02 {
        let inner = api::config_from_validation_data(data, default_hw_extra())?;
        return Ok(Arc::new(HwConfig { inner }));
    }

    Err(ImError::msg("Unrecognized activation payload"))
}

/// Raw validation data plus explicitly supplied version metadata.
///
/// Used when the version metadata arrived alongside the blob, rather than falling back to
/// [`default_hw_extra`].
#[uniffi::export]
pub fn config_from_validation_data(data: Vec<u8>, extra: HwExtra) -> Result<Arc<HwConfig>> {
    let inner = api::config_from_validation_data(data, extra.into())?;
    Ok(Arc::new(HwConfig { inner }))
}

/// A hardware identity that defers to a registration relay instead of holding a Mac's identifiers.
///
/// The other half of what OpenBubbles offers, and the half that does not need OpenAbsinthe. A relay
/// is a Mac or an iPhone running an agent that hands out validation data on request, so
/// `RelayConfig::generate_validation_data` fetches from `host` rather than deriving anything from
/// hardware locally — which is the step this build cannot perform.
///
/// The trade is reachability, which is why `docs/IMESSAGE_PLAN.md` section 5 passed on it: a
/// scanned identity is read once and its Mac never contacted again, while a relay has to answer
/// every time this device registers.
///
/// `token` is the relay operator's access token, where it wants one — Beeper's public relay
/// requires theirs on every call, and a self-hosted relay generally requires none.
#[uniffi::export(async_runtime = "tokio")]
pub async fn config_from_relay(
    code: String,
    host: String,
    token: Option<String>,
) -> Result<Arc<HwConfig>> {
    let inner = api::config_from_relay(code, host, &token).await?;
    Ok(Arc::new(HwConfig { inner }))
}

#[uniffi::export]
impl HwConfig {
    /// Everything known about the machine, for the device page in settings.
    pub fn details(&self) -> Result<DeviceDetails> {
        let d = api::get_device_details(&self.inner)?;
        Ok(DeviceDetails {
            name: d.name,
            serial: d.serial,
            os_version: d.os_version,
            product_name: d.product_name,
            board_id: d.board_id,
            os_build: d.os_build,
            platform_uuid: d.platform_uuid,
            root_disk_uuid: d.root_disk_uuid,
            mlb: d.mlb,
            rom: d.rom,
            mac_address: d.mac_address,
            device_id: d.device_id,
            protocol_version: d.protocol_version,
            icloud_user_agent: d.icloud_user_agent,
            aoskit_version: d.aoskit_version,
            relay_host: d.relay_host,
            exportable: d.exportable,
        })
    }

    /// Identifying details, for the confirmation step of setup.
    pub fn device_info(&self) -> Result<DeviceInfo> {
        let info = api::get_device_info(&self.inner)?;
        Ok(DeviceInfo {
            name: info.name,
            serial: info.serial,
            os_version: info.os_version,
        })
    }

    /// Re-encode this identity as an `OABS` payload so it can be handed to another device.
    ///
    /// `shareable` is recorded for the *recipient's* benefit — a config marked single-use is meant
    /// to be consumed once rather than passed on again.
    pub fn to_activation_payload(&self, shareable: bool) -> Result<Vec<u8>> {
        let info = api::get_device_info(&self.inner)?;
        let Some(encoded) = info.encoded_data else {
            return Err(ImError::msg("This identity cannot be exported"));
        };

        let mut out = Vec::with_capacity(encoded.len() + 5);
        out.extend_from_slice(b"OABS");
        out.push(if shareable { 0 } else { 1 });
        out.extend_from_slice(&encoded);
        Ok(out)
    }
}

/// Prepare `dir` as the state directory and start logging into it.
///
/// Must run once per process before anything else here; every other entry point assumes the
/// directory exists and that the keystore has been wired up.
#[uniffi::export]
pub fn init(dir: String) {
    api::do_first_time_init(dir);
}

/// The hardware identity persisted by a previous run, if setup ever completed.
///
/// Reads the same `hw_info.plist` that `setup_push` rewrites on every reconnect, so it always
/// reflects live push state rather than a stale snapshot.
#[uniffi::export]
pub fn load_hw_config(dir: String) -> Option<Arc<HwConfig>> {
    let saved = api::read_hardware(dir)?;
    Some(Arc::new(HwConfig { inner: saved.os_config }))
}

/// Persist `config` as this device's hardware identity, with a freshly generated NGM identity.
///
/// Called once when setup accepts a scanned code, before any push connection exists.
#[uniffi::export(async_runtime = "tokio")]
pub async fn save_hw_config(dir: String, config: Arc<HwConfig>) -> Result<()> {
    let identity = api::new_ngm_identity()?;
    api::set_identity(dir, &config.inner, &identity).await;
    Ok(())
}

/// The Apple ID a previous run signed in as, shown as "continue as ..." in setup.
#[uniffi::export]
pub fn saved_apple_id(dir: String) -> Option<String> {
    api::get_available_user(dir)
}

/// Whether the app can skip setup and go straight to the chat list.
///
/// Saved users alone are not enough: without hardware behind them they are leftovers from an
/// identity that has since been cleared, and would fail on the first send.
#[uniffi::export]
pub fn is_registered(dir: String) -> bool {
    let has_users = api::restore_users(dir.clone()).is_some_and(|users| !users.is_empty());
    has_users && api::read_hardware(dir).is_some()
}
