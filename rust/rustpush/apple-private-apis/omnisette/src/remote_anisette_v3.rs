
// Implementing the SideStore Anisette v3 protocol

use std::{collections::HashMap, fs, io::Cursor, path::PathBuf, time::Duration};

use base64::engine::general_purpose;
use chrono::{DateTime, SubsecRound, Utc};
use log::{debug, warn};
use plist::{Data, Dictionary};
use reqwest::{Certificate, Client, ClientBuilder, Proxy, RequestBuilder};
use serde::{Deserialize, Deserializer, Serialize, Serializer};
use rand::Rng;
use sha2::{Sha256, Digest};
use tokio_tungstenite::{connect_async, tungstenite::Message};
use uuid::Uuid;
use futures_util::{stream::StreamExt, SinkExt};
use std::fmt::Write;
use base64::Engine;
use async_trait::async_trait;

use crate::{AnisetteError, AnisetteProvider, LoginClientInfo};

const APPLE_ROOT: &[u8] = include_bytes!("../../icloud-auth/src/apple_root.der");

fn plist_to_string<T: serde::Serialize>(value: &T) -> Result<String, plist::Error> {
    plist_to_buf(value).map(|val| String::from_utf8(val).unwrap())
}

fn plist_to_buf<T: serde::Serialize>(value: &T) -> Result<Vec<u8>, plist::Error> {
    let mut buf: Vec<u8> = Vec::new();
    let writer = Cursor::new(&mut buf);
    plist::to_writer_xml(writer, &value)?;
    Ok(buf)
}

fn bin_serialize<S>(x: &[u8], s: S) -> Result<S::Ok, S::Error>
where
    S: Serializer,
{
    s.serialize_bytes(x)
}

fn bin_serialize_opt<S>(x: &Option<Vec<u8>>, s: S) -> Result<S::Ok, S::Error>
where
    S: Serializer,
{
    x.clone().map(|i| Data::new(i)).serialize(s)
}

fn bin_deserialize_opt<'de, D>(d: D) -> Result<Option<Vec<u8>>, D::Error>
where
    D: Deserializer<'de>,
{
    let s: Option<Data> = Deserialize::deserialize(d)?;
    Ok(s.map(|i| i.into()))
}

fn bin_deserialize_16<'de, D>(d: D) -> Result<[u8; 16], D::Error>
where
    D: Deserializer<'de>,
{
    let s: Data = Deserialize::deserialize(d)?;
    let s: Vec<u8> = s.into();
    Ok(s.try_into().unwrap())
}

fn encode_hex(bytes: &[u8]) -> String {
    let mut s = String::with_capacity(bytes.len() * 2);
    for &b in bytes {
        write!(&mut s, "{:02x}", b).unwrap();
    }
    s
}
fn base64_encode(data: &[u8]) -> String {
    general_purpose::STANDARD.encode(data)
}

fn base64_decode(data: &str, stage: &str) -> Result<Vec<u8>, AnisetteError> {
    general_purpose::STANDARD.decode(data.trim()).map_err(|err| AnisetteError::ProvisioningFailed {
        stage: stage.to_string(),
        message: format!("the anisette server sent something that isn't base64: {err}"),
    })
}

/// The `Response` dictionary Apple wraps its provisioning answers in.
fn apple_response(text: &str, stage: &str) -> Result<Dictionary, AnisetteError> {
    plist::Value::from_reader(Cursor::new(text))?
        .as_dictionary()
        .and_then(|body| body.get("Response"))
        .and_then(|response| response.as_dictionary())
        .cloned()
        .ok_or_else(|| AnisetteError::AppleProvisioningRefused {
            stage: stage.to_string(),
            key: "Response".to_string(),
            body: snippet(text),
        })
}

/// Pull a field Apple is contractually obliged to send, keeping the body when it didn't.
///
/// A refusal comes back shaped like a success — a `Response` dictionary, just carrying a `Status`
/// with an error code where the token should be. Unwrapping here would abort the process across the
/// FFI boundary and throw away the one number that says *why*.
fn require_str(dict: &Dictionary, key: &str, stage: &str, body: &str) -> Result<String, AnisetteError> {
    dict.get(key)
        .and_then(|value| value.as_string())
        .map(str::to_string)
        .ok_or_else(|| AnisetteError::AppleProvisioningRefused {
            stage: stage.to_string(),
            key: key.to_string(),
            body: snippet(body),
        })
}

/// Enough of a response body to carry the error code, without pasting a plist into a toast.
fn snippet(text: &str) -> String {
    const LIMIT: usize = 512;
    match text.char_indices().nth(LIMIT) {
        Some((end, _)) => format!("{}…", &text[..end]),
        None => text.to_string(),
    }
}


#[derive(Serialize, Deserialize)]
pub struct AnisetteState {
    #[serde(serialize_with = "bin_serialize", deserialize_with = "bin_deserialize_16")]
    keychain_identifier: [u8; 16],
    #[serde(serialize_with = "bin_serialize_opt", deserialize_with = "bin_deserialize_opt")]
    adi_pb: Option<Vec<u8>>,
}

impl Default for AnisetteState {
    fn default() -> Self {
        AnisetteState {
            keychain_identifier: rand::thread_rng().gen::<[u8; 16]>(),
            adi_pb: None
        }
    }
}

impl AnisetteState {
    pub fn new() -> AnisetteState {
        AnisetteState::default()
    }

    pub fn is_provisioned(&self) -> bool {
        self.adi_pb.is_some()
    }

    /// Start over under a new identifier, dropping whatever a failed attempt left behind.
    pub fn reset_identifier(&mut self) {
        self.keychain_identifier = rand::thread_rng().gen();
        self.adi_pb = None;
    }

    fn md_lu(&self) -> [u8; 32] {
        let mut hasher = Sha256::new();
        hasher.update(&self.keychain_identifier);
        hasher.finalize().into()
    }

    fn device_id(&self) -> String {
        Uuid::from_bytes(self.keychain_identifier).to_string()
    }
}
pub struct AnisetteClient {
    login_info: LoginClientInfo,
    url: String
}

#[derive(Serialize)]
#[serde(rename_all = "PascalCase")]
struct ProvisionBodyData {
    header: Dictionary,
    request: Dictionary,
}

#[derive(Debug)]
pub struct AnisetteData {
    machine_id: String,
    one_time_password: String,
    routing_info: String,
    device_description: String,
    local_user_id: String,
    device_unique_identifier: String
}

impl AnisetteData {
    pub fn get_headers(&self) -> HashMap<String, String> {
        let dt: DateTime<Utc> = Utc::now().round_subsecs(0);

        HashMap::from_iter([
            ("X-Apple-I-Client-Time".to_string(), dt.format("%+").to_string().replace("+00:00", "Z")),
            ("X-Apple-I-TimeZone".to_string(), "UTC".to_string()),
            ("X-Apple-Locale".to_string(), "en_US".to_string()),
            ("X-Apple-I-MD-RINFO".to_string(), self.routing_info.clone()),
            ("X-Apple-I-MD-LU".to_string(), self.local_user_id.clone()),
            ("X-Mme-Device-Id".to_string(), self.device_unique_identifier.clone()),
            ("X-Apple-I-MD".to_string(), self.one_time_password.clone()),
            ("X-Apple-I-MD-M".to_string(), self.machine_id.clone()),
            ("X-Mme-Client-Info".to_string(), self.device_description.clone()),
        ].into_iter())
    }
}

fn make_reqwest() -> Result<Client, AnisetteError> {
    Ok(ClientBuilder::new()
        .http1_title_case_headers()
        .add_root_certificate(Certificate::from_der(APPLE_ROOT)?)
        // .proxy(Proxy::https("https://localhost:8080").unwrap())
        // .danger_accept_invalid_certs(true)
        .build()?)
}

impl AnisetteClient {
    pub async fn new(url: String, login_info: LoginClientInfo) -> Result<AnisetteClient, AnisetteError> {
        Ok(AnisetteClient {
            login_info,
            url
        })
    }

    fn build_apple_request(&self, state: &AnisetteState, mut builder: RequestBuilder) -> RequestBuilder {
        let dt: DateTime<Utc> = Utc::now().round_subsecs(0);

        // missing: Connection, Accept-Encdoing
        builder = builder.header("User-Agent", &self.login_info.akd_user_agent)
            .header("X-Apple-Baa-E", "-10000")
            .header("X-Apple-I-MD-LU", encode_hex(&state.md_lu()))
            .header("X-Mme-Device-Id", state.device_id())
            .header("X-Apple-Baa-Avail", "2")
            .header("X-Mme-Client-Info", &self.login_info.mme_client_info)
            .header("X-Apple-I-Client-Time", dt.format("%+").to_string())
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("X-Apple-Client-App-Name", "akd")
            .header("Accept", "*/*")
            .header("Content-Type", "application/x-www-form-urlencoded") // not a bug, it's how you *think different*
            .header("X-Apple-Baa-UE", "AKAuthenticationError:-7066|com.apple.devicecheck.error.baa:-10000")
            .header("X-Apple-Host-Baa-E", "-7066");

        for item in &self.login_info.hardware_headers {
            builder = builder.header(item.0, item.1);
        }

        builder
    }

    pub async fn get_headers(&self, state: &AnisetteState) -> Result<AnisetteData, AnisetteError> {
        let path = format!("{}/v3/get_headers", self.url);
        let http_client = make_reqwest()?;

        #[derive(Serialize)]
        struct GetHeadersBody {
            identifier: String,
            adi_pb: String,
        }
        let body = GetHeadersBody {
            identifier: base64_encode(&state.keychain_identifier),
            adi_pb: base64_encode(state.adi_pb.as_ref().ok_or(AnisetteError::AnisetteNotProvisioned)?),
        };

        #[derive(Deserialize)]
        #[serde(tag = "result")]
        enum AnisetteHeaders {
            GetHeadersError {
                message: String
            },
            Headers {
                #[serde(rename = "X-Apple-I-MD-M")]
                machine_id: String,
                #[serde(rename = "X-Apple-I-MD")]
                one_time_password: String,
                #[serde(rename = "X-Apple-I-MD-RINFO")]
                routing_info: String,
            }
        }

        let headers = http_client.post(path)
            .json(&body)
            .send().await?
            .json::<AnisetteHeaders>().await?;
        match headers {
            AnisetteHeaders::GetHeadersError { message } => {
                if message.contains("-45061") {
                    Err(AnisetteError::AnisetteNotProvisioned)
                } else {
                    // Anything else is still the server telling us something; panicking here takes
                    // the whole app down instead of letting sign-in report why it stopped.
                    Err(AnisetteError::AnisetteServerError(message))
                }
            },
            AnisetteHeaders::Headers { machine_id, one_time_password, routing_info } => {
                Ok(AnisetteData {
                    machine_id,
                    one_time_password,
                    routing_info,
                    device_description: self.login_info.mme_client_info.clone(),
                    local_user_id: encode_hex(&state.md_lu()),
                    device_unique_identifier: state.device_id()
                })
            }
        }
    }

    pub async fn provision(&self, state: &mut AnisetteState) -> Result<(), AnisetteError> {
        debug!("Provisioning Anisette");
        let http_client = make_reqwest()?;
        let resp = self.build_apple_request(&state, http_client.get("https://gsa.apple.com/grandslam/GsService2/lookup"))
            .send().await?;
        let text = resp.text().await?;

        let protocol_val = plist::Value::from_reader(Cursor::new(text.as_str()))?;
        let urls = protocol_val.as_dictionary()
            .and_then(|body| body.get("urls"))
            .and_then(|urls| urls.as_dictionary())
            .ok_or_else(|| AnisetteError::AppleProvisioningRefused {
                stage: "lookup".to_string(),
                key: "urls".to_string(),
                body: snippet(&text),
            })?;

        let start_provisioning_url = require_str(urls, "midStartProvisioning", "lookup", &text)?;
        let end_provisioning_url = require_str(urls, "midFinishProvisioning", "lookup", &text)?;
        debug!("Got provisioning urls: {} and {}", start_provisioning_url, end_provisioning_url);

        let provision_ws_url = format!("{}/v3/provisioning_session", self.url).replace("https://", "wss://");
        let (mut connection, _) = connect_async(&provision_ws_url).await?;


        #[derive(Deserialize)]
        #[serde(tag = "result")]
        enum ProvisionInput {
            GiveIdentifier,
            GiveStartProvisioningData,
            GiveEndProvisioningData {
                #[allow(dead_code)] // it's not even dead, rust just has problems
                cpim: String
            },
            ProvisioningSuccess {
                #[allow(dead_code)] // it's not even dead, rust just has problems
                adi_pb: String
            },
            // The server reports its own failures down this same socket, and they are the common
            // outcome when provisioning goes wrong. Leaving them out of the enum turned every one
            // of them into a serde complaint about unknown variants, which buried the part worth
            // reading: the ADI error code and the request id the server operator can grep for.
            InvalidIdentifier,
            StartProvisioningError { message: String },
            EndProvisioningError { message: String },
            Timeout,
            #[serde(other)]
            Unknown,
        }

        let mut adi_pb = None;
        loop {
            let data = match connection.next().await {
                Some(Ok(data)) => data,
                Some(Err(err)) => return Err(err.into()),
                // The server hangs up right after reporting an error. `next()` then yields `None`
                // forever, and this loop used to spin on it burning a core.
                None => break,
            };
            if data.is_text() {
                let txt = data.to_text().unwrap();
                let msg: ProvisionInput = serde_json::from_str(txt)?;
                match msg {
                    ProvisionInput::GiveIdentifier => {
                        #[derive(Serialize)]
                        struct Identifier {
                            identifier: String // base64
                        }
                        let identifier = Identifier { identifier: base64_encode(&state.keychain_identifier) };
                        connection.send(Message::Text(serde_json::to_string(&identifier)?)).await?;
                    },
                    ProvisionInput::GiveStartProvisioningData => {
                        let http_client = make_reqwest()?;
                        let body_data = ProvisionBodyData { header: Dictionary::new(), request: Dictionary::new() };
                        let resp = self.build_apple_request(state, http_client.post(&start_provisioning_url))
                            .body(plist_to_string(&body_data)?)
                            .send().await?;
                        let text = resp.text().await?;

                        let response = apple_response(&text, "midStartProvisioning")?;
                        let spim = require_str(&response, "spim", "midStartProvisioning", &text)?;

                        debug!("GiveStartProvisioningData");
                        #[derive(Serialize)]
                        struct Spim {
                            spim: String // base64
                        }
                        let spim = Spim { spim };
                        connection.send(Message::Text(serde_json::to_string(&spim)?)).await?;
                    },
                    ProvisionInput::GiveEndProvisioningData { cpim } => {
                        let http_client = make_reqwest()?;
                        let body_data = ProvisionBodyData { header: Dictionary::new(), request: Dictionary::from_iter([("cpim", cpim)].into_iter()) };
                        let resp = self.build_apple_request(state, http_client.post(&end_provisioning_url))
                            .body(plist_to_string(&body_data)?)
                            .send().await?;
                        let text = resp.text().await?;

                        let response = apple_response(&text, "midFinishProvisioning")?;

                        debug!("GiveEndProvisioningData");

                        #[derive(Serialize)]
                        struct EndProvisioning {
                            ptm: String,
                            tk: String,
                        }
                        let end_provisioning = EndProvisioning {
                            ptm: require_str(&response, "ptm", "midFinishProvisioning", &text)?,
                            tk: require_str(&response, "tk", "midFinishProvisioning", &text)?,
                        };
                        connection.send(Message::Text(serde_json::to_string(&end_provisioning)?)).await?;
                    },
                    ProvisionInput::ProvisioningSuccess { adi_pb: encoded } => {
                        debug!("ProvisioningSuccess");
                        adi_pb = Some(base64_decode(&encoded, "ProvisioningSuccess")?);
                        connection.close(None).await?;
                        break;
                    },
                    ProvisionInput::InvalidIdentifier => return Err(AnisetteError::ProvisioningFailed {
                        stage: "GiveIdentifier".to_string(),
                        message: "the anisette server rejected our identifier".to_string(),
                    }),
                    ProvisionInput::StartProvisioningError { message } => return Err(AnisetteError::ProvisioningFailed {
                        stage: "StartProvisioning".to_string(),
                        message,
                    }),
                    ProvisionInput::EndProvisioningError { message } => return Err(AnisetteError::ProvisioningFailed {
                        stage: "EndProvisioning".to_string(),
                        message,
                    }),
                    ProvisionInput::Timeout => return Err(AnisetteError::ProvisioningFailed {
                        stage: "session".to_string(),
                        message: "the anisette server gave up waiting for us".to_string(),
                    }),
                    ProvisionInput::Unknown => return Err(AnisetteError::ProvisioningFailed {
                        stage: "session".to_string(),
                        message: format!("the anisette server sent a step we don't know: {}", snippet(txt)),
                    }),
                }
            } else if data.is_close() {
                break;
            }
        }

        // Falling out of the loop means the socket ended before `ProvisioningSuccess`. Returning
        // `Ok` there left `adi_pb` unset and the caller looping back through provisioning forever.
        state.adi_pb = Some(adi_pb.ok_or_else(|| AnisetteError::ProvisioningFailed {
            stage: "session".to_string(),
            message: "the anisette server hung up before provisioning finished".to_string(),
        })?);

        Ok(())
    }
}

/// Provisioning fails the whole sign-in, so don't hand the user a dead end over one bad attempt.
const PROVISION_ATTEMPTS: u32 = 3;

/// Provision, giving a transient failure a couple more chances under a fresh identifier.
///
/// The identifier matters: the anisette server names the ADI provisioning directory after it and
/// deletes that directory as the failed session unwinds. A retry that reuses the identifier can
/// land inside that teardown and fail exactly the same way, which is what an `EndProvisioningError`
/// looks like from here. Nothing is persisted until provisioning succeeds, so rolling the
/// identifier costs nothing.
async fn provision_retrying(client: &AnisetteClient, state: &mut AnisetteState) -> Result<(), AnisetteError> {
    let mut attempt = 1;
    loop {
        match client.provision(state).await {
            Ok(()) => return Ok(()),
            Err(err) if attempt >= PROVISION_ATTEMPTS || !err.is_transient() => return Err(err),
            Err(err) => {
                warn!("Anisette provisioning attempt {attempt} failed, retrying: {err}");
                state.reset_identifier();
                tokio::time::sleep(Duration::from_secs(1 << attempt)).await;
                attempt += 1;
            }
        }
    }
}


pub struct RemoteAnisetteProviderV3 {
    client_url: String,
    client: Option<AnisetteClient>,
    pub state: Option<AnisetteState>,
    configuration_path: PathBuf,
    info: LoginClientInfo
}

impl RemoteAnisetteProviderV3 {
    pub fn new(url: String, info: LoginClientInfo, configuration_path: PathBuf) -> RemoteAnisetteProviderV3 {
        RemoteAnisetteProviderV3 {
            client_url: url,
            client: None,
            state: None,
            configuration_path,
            info
        }
    }
}

impl AnisetteProvider for RemoteAnisetteProviderV3 {
    async fn get_anisette_headers(&mut self) -> Result<HashMap<String, String>, AnisetteError> {
        if self.client.is_none() {
            self.client = Some(AnisetteClient::new(self.client_url.clone(), self.info.clone()).await?);
        }
        let client = self.client.as_ref().unwrap();

        fs::create_dir_all(&self.configuration_path)?;

        let config_path = self.configuration_path.join("state.plist");
        if self.state.is_none() {
            self.state = Some(if let Ok(text) = plist::from_file(&config_path) {
                text
            } else {
                AnisetteState::new()
            });
        }

        let state = self.state.as_mut().unwrap();
        if !state.is_provisioned() {
            provision_retrying(client, state).await?;
            plist::to_file_xml(&config_path, state)?;
        }
        let data = match client.get_headers(&state).await {
            Ok(data) => data,
            Err(err) => {
                if matches!(err, AnisetteError::AnisetteNotProvisioned) {
                    state.adi_pb = None;
                    provision_retrying(client, state).await?;
                    plist::to_file_xml(config_path, state)?;
                    client.get_headers(&state).await?
                } else { return Err(err) }
            },
        };
        Ok(data.get_headers())
    }
}



