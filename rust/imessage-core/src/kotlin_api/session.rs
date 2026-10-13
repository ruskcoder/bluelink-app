//! The live connection to Apple, and the login negotiation that gets us one.

use std::sync::Arc;

use rustpush::{
    APSConnection, APSMessage, AppleAccount, ArcAnisetteClient, CircleClientSession, DebugMutex,
    DefaultAnisetteProvider, IDSNGMIdentity, IDSUser, IMClient, IdmsAuthListener, LoginState,
    VerifyBody,
    statuskit::{ChannelInterestToken, StatusKitClient},
};
use tokio::sync::{broadcast, mpsc, Mutex as AsyncMutex};

// `Inbox` stays crate-private, so it is reached through the sibling module rather than the glob
// re-export in `mod.rs`, which only carries public items.
use super::{hardware::HwConfig, messaging::Inbox, ImError, IncomingEvent, Result};
use crate::api::api::{self, JoinedOSConfig};

/// Where the login negotiation stands, and what the UI has to collect next.
///
/// This is the flattened form of `icloud_auth`'s `LoginState`. That enum describes Apple's protocol
/// states, several of which are ours to resolve without asking the user anything: re-authenticating
/// with saved credentials, requesting a code, auto-selecting a sole trusted number. Those are
/// driven to completion inside [`Session::advance`], so what surfaces here is only ever a state the
/// user actually has to act on.
#[derive(uniffi::Enum, Debug)]
pub enum LoginStep {
    /// A code was pushed to the account's trusted devices.
    NeedsDeviceCode,
    /// More than one trusted number is available; the user picks before a code is sent.
    NeedsSmsChoice { targets: Vec<PhoneTarget> },
    /// A code was texted to the chosen number.
    NeedsSmsCode,
    /// Apple wants something that cannot be done in-app, e.g. accepting updated terms.
    NeedsExtraStep { detail: String },
    LoggedIn,
}

#[derive(uniffi::Record, Debug, Clone)]
pub struct PhoneTarget {
    pub id: u32,
    pub number: String,
    pub last_two_digits: String,
}

type Account = Arc<DebugMutex<AppleAccount<DefaultAnisetteProvider>>>;

/// State that only exists between `login()` and a terminal [`LoginStep`].
#[derive(Default)]
struct Pending {
    account: Option<Account>,
    /// Held across the device-code exchange; `verify_2fa` drives it to completion.
    circle: Option<CircleClientSession<DefaultAnisetteProvider>>,
    /// Set once Apple has texted a code, and required in order to verify it.
    sms_body: Option<VerifyBody>,
    /// Subscribed *before* requesting a device code, so the push carrying it cannot be missed.
    aps_watch: Option<broadcast::Receiver<APSMessage>>,
    /// Kept so a `NeedsLogin` bounce mid-flow can re-authenticate without re-prompting.
    creds: Option<(String, String)>,
    user: Option<IDSUser>,
}

/// The presence client, and whether we have already found out we cannot have one.
///
/// Built on demand rather than in [`open`]: it needs a restored Apple Account, which needs the
/// credential written at login, and a session exists long before either — during setup there is no
/// account to restore and never will be until the user has signed in. Once we have looked and come
/// up empty there is no point looking again on every push, which is what the third state is for —
/// but only for a while: a sign-in that finishes after the session opened would otherwise leave
/// Focus switched off until the process restarts.
pub(crate) enum Presence {
    Unbuilt,
    /// Not signed in, or no stored credential to rebuild an account from, as of this instant.
    Unavailable(std::time::Instant),
    Ready(Arc<StatusKitClient<DefaultAnisetteProvider>>),
}

/// How long a failed presence build is trusted before it is tried again.
const PRESENCE_RETRY: std::time::Duration = std::time::Duration::from_secs(60);

/// A live connection to Apple: push socket, hardware identity, and once registered, an `IMClient`.
///
/// Deliberately leaner than `api::api::SharedPushState`, which additionally builds CloudKit,
/// keychain, passwords, FindMy, shared streams and FaceTime clients. None of that is reachable
/// from this app, and each one is a network dependency that can fail during setup. StatusKit is
/// the one exception, and it is built lazily for the same reason the others are absent: nothing
/// about opening a connection should depend on it.
#[derive(uniffi::Object)]
pub struct Session {
    pub(crate) dir: String,
    pub(crate) config: JoinedOSConfig,
    pub(crate) identity: IDSNGMIdentity,
    pub(crate) conn: APSConnection,
    pub(crate) anisette: ArcAnisetteClient<DefaultAnisetteProvider>,
    pub(crate) idms: Arc<IdmsAuthListener>,
    pending: AsyncMutex<Pending>,
    pub(crate) client: AsyncMutex<Option<Arc<IMClient>>>,
    /// Where `dispatch` posts delivery outcomes so they surface on the same stream as inbound
    /// traffic, instead of the UI needing a second channel for its own sends.
    pub(crate) local_tx: mpsc::Sender<IncomingEvent>,
    /// Guarded rather than owned by the caller because there is exactly one consumer; the lock is
    /// what makes `next_event` safe to call from an ordinary Kotlin `Flow`.
    pub(crate) inbox: AsyncMutex<Inbox>,
    /// Who tells us that somebody has a Focus turned on. See [`Presence`].
    pub(crate) presence: AsyncMutex<Presence>,
    /// Our subscription to the presence channels of the people we are watching.
    ///
    /// Apple only pushes a status change to devices that have asked for that person's channel, and
    /// the interest is released when this is dropped — so it has to be kept somewhere for as long
    /// as the app cares, which is the whole time it is running. Replaced wholesale by
    /// [`Session::watch_focus_status`] rather than added to: the caller passes everyone it is
    /// interested in, so the old token going out of scope is exactly the right unsubscribe.
    pub(crate) focus_interest: AsyncMutex<Option<ChannelInterestToken>>,
    /// Who the app last asked to watch, as it spelled them — kept so the subscription can be
    /// rebuilt when somebody new shares their Focus with us mid-session.
    pub(crate) focus_handles: AsyncMutex<Vec<String>>,
    /// Attachments that reached Apple for a message that has not been confirmed as sent yet.
    ///
    /// The upload and the send are one call from the app's side, so a send that fails after its
    /// upload succeeded used to pay for the whole upload again on the next attempt. Kept here, the
    /// retry picks the finished upload back up. See [`Session::upload`](super::messaging).
    pub(crate) uploads: std::sync::Mutex<super::messaging::Uploads>,
}

/// Open the push connection, restoring saved push state if there is any.
///
/// Registration is not implied: a session exists as soon as we can talk to APS, which is a
/// prerequisite for logging in at all.
#[uniffi::export(async_runtime = "tokio")]
pub async fn connect(dir: String) -> Result<Arc<Session>> {
    let Some(hardware) = api::read_hardware(dir.clone()) else {
        return Err(ImError::NeedsHardware);
    };

    let identity = api::decode_identity(hardware.identity.as_ref())?;
    let config = hardware.os_config.clone();
    open(dir, config, identity, Some(hardware.push.clone())).await
}

/// Connect using a hardware identity that has just been scanned, rather than one read back.
///
/// Setup calls this instead of [`connect`] so the freshly parsed config goes straight to
/// `setup_push` — which is also what writes a *complete* `hw_info.plist`, with a real push token in
/// it. Going out through disk and back in first would mean reading a state that has no token yet,
/// and that is a materially harder thing to keep readable than it looks.
#[uniffi::export(async_runtime = "tokio")]
pub async fn connect_with(dir: String, config: Arc<HwConfig>) -> Result<Arc<Session>> {
    // A new hardware identity gets a new NGM identity; reusing one registered against different
    // hardware is what Apple rejects.
    let identity = api::new_ngm_identity()?;
    open(dir, config.inner.clone(), identity, None).await
}

async fn open(
    dir: String,
    config: JoinedOSConfig,
    identity: IDSNGMIdentity,
    push: Option<rustpush::APSState>,
) -> Result<Arc<Session>> {
    let (conn, error) = api::setup_push(&config, &identity, push, dir.clone()).await;
    if let Some(error) = error {
        return Err(error.into());
    }

    let anisette = api::make_anisette(dir.clone(), &config, &conn).await;
    let idms = api::make_idms(&conn).await;

    // Pick up an existing registration if setup completed on a previous run.
    let client = match api::restore_users(dir.clone()) {
        Some(users) if !users.is_empty() => {
            Some(api::make_imclient(dir.clone(), &conn, &users, &identity).await)
        }
        _ => None,
    };

    // Subscribe before returning, so pushes arriving between `connect` and the first `next_event`
    // are buffered by the broadcast channel rather than dropped.
    let aps = api::subscribe_conn(&conn);
    let (local_tx, local_rx) = mpsc::channel(256);

    Ok(Arc::new(Session {
        dir,
        config,
        identity,
        conn,
        anisette,
        idms,
        pending: AsyncMutex::new(Pending::default()),
        client: AsyncMutex::new(client),
        local_tx,
        inbox: AsyncMutex::new(Inbox { aps, local: local_rx }),
        presence: AsyncMutex::new(Presence::Unbuilt),
        focus_interest: AsyncMutex::new(None),
        focus_handles: AsyncMutex::new(Vec::new()),
        uploads: Default::default(),
    }))
}

impl Session {
    /// The presence client, built the first time anything asks for it.
    ///
    /// `None` whenever this account cannot have one — before registration, or on an install whose
    /// stored credential has been cleared. Focus status is a courtesy on top of messaging, so every
    /// caller treats its absence as "nobody has a Focus on" rather than as a failure.
    pub(crate) async fn presence(&self) -> Option<Arc<StatusKitClient<DefaultAnisetteProvider>>> {
        let mut held = self.presence.lock().await;
        let retry = match &*held {
            Presence::Unbuilt => true,
            Presence::Unavailable(at) => at.elapsed() >= PRESENCE_RETRY,
            Presence::Ready(_) => false,
        };
        if retry {
            *held = match self.build_presence().await {
                Some(client) => Presence::Ready(client),
                None => Presence::Unavailable(std::time::Instant::now()),
            };
        }
        match &*held {
            Presence::Ready(client) => Some(client.clone()),
            _ => None,
        }
    }

    async fn build_presence(&self) -> Option<Arc<StatusKitClient<DefaultAnisetteProvider>>> {
        let client = self.client.lock().await.clone()?;
        // Rebuilt from `gsa.plist`, which login wrote. Nothing here goes to the network: the token
        // provider only authenticates when a token is actually wanted, and a token is only wanted
        // in order to *publish* our own status — which this app never does.
        let account =
            api::restore_account(self.dir.clone(), &self.anisette, &self.config, &self.conn).await?;
        let tokens = api::make_token_provider(&account, &self.config);
        Some(api::make_statuskit(self.dir.clone(), &tokens, &self.conn, &self.config, &client).await)
    }
}

impl Session {
    /// Drive Apple's login state machine until it needs the user, or finishes.
    ///
    /// Loops because one step cascades into the next: `NeedsLogin` re-authenticates, which may land
    /// on `NeedsDevice2FA`, which requests a code and becomes `Needs2FAVerification`. Only states
    /// the user must answer escape this loop.
    async fn advance(&self, mut state: LoginState) -> Result<LoginStep> {
        loop {
            match state {
                LoginState::LoggedIn => {
                    let account = self.pending.lock().await.account.clone();
                    let needs_user = self.pending.lock().await.user.is_none();
                    if needs_user {
                        if let Some(account) = account {
                            let user =
                                api::try_icloud_login(self.dir.clone(), &self.config, &account)
                                    .await?;
                            self.pending.lock().await.user = user;
                        }
                    }
                    return Ok(LoginStep::LoggedIn);
                }

                LoginState::NeedsExtraStep(detail) => {
                    return Ok(LoginStep::NeedsExtraStep { detail })
                }

                LoginState::NeedsLogin => {
                    let creds = self.pending.lock().await.creds.clone();
                    let (account, next) = api::try_auth(
                        self.dir.clone(),
                        &self.config,
                        &self.conn,
                        &self.anisette,
                        creds,
                    )
                    .await?;
                    let user =
                        api::try_icloud_login(self.dir.clone(), &self.config, &account).await?;

                    {
                        let mut pending = self.pending.lock().await;
                        pending.account = Some(account);
                        pending.user = user;
                    }

                    state = next;
                }

                LoginState::NeedsDevice2FA => {
                    let account = self.require_account().await?;

                    // Subscribe before asking for the code: the push carrying it can land before
                    // `send_2fa_to_devices` returns, and a broadcast receiver only sees messages
                    // published after it subscribed.
                    let watch = api::subscribe_conn(&self.conn);

                    let (circle, next, _sid) =
                        api::send_2fa_to_devices(&account, &self.conn).await?;

                    {
                        let mut pending = self.pending.lock().await;
                        pending.aps_watch = Some(watch);
                        pending.circle = Some(circle);
                    }

                    state = next;
                }

                LoginState::Needs2FAVerification => return Ok(LoginStep::NeedsDeviceCode),

                LoginState::NeedsSMS2FA => {
                    let account = self.require_account().await?;
                    let (numbers, new_state) = api::get_2fa_sms_opts(&account).await?;

                    if let Some(new_state) = new_state {
                        state = new_state;
                        continue;
                    }

                    if numbers.len() == 1 {
                        // Nothing to choose between, so skip the picker entirely.
                        let circle = self.pending.lock().await.circle.take();
                        state = api::send_2fa_sms(circle, &account, numbers[0].id).await?;
                        continue;
                    }

                    return Ok(LoginStep::NeedsSmsChoice {
                        targets: numbers
                            .into_iter()
                            .map(|n| PhoneTarget {
                                id: n.id,
                                number: n.number_with_dial_code,
                                last_two_digits: n.last_two_digits,
                            })
                            .collect(),
                    });
                }

                LoginState::NeedsSMS2FAVerification(body) => {
                    self.pending.lock().await.sms_body = Some(body);
                    return Ok(LoginStep::NeedsSmsCode);
                }
            }
        }
    }

    async fn require_account(&self) -> Result<Account> {
        self.pending
            .lock()
            .await
            .account
            .clone()
            .ok_or_else(|| ImError::msg("No sign-in is in progress"))
    }

    pub(crate) async fn require_client(&self) -> Result<Arc<IMClient>> {
        self.client
            .lock()
            .await
            .clone()
            .ok_or(ImError::NotRegistered)
    }
}

#[uniffi::export(async_runtime = "tokio")]
impl Session {
    /// Sign in with an Apple ID and password.
    pub async fn login(&self, apple_id: String, password: String) -> Result<LoginStep> {
        // Apple only accepts a lowercased Apple ID.
        let apple_id = apple_id.to_lowercase();

        let (account, state) = api::try_auth(
            self.dir.clone(),
            &self.config,
            &self.conn,
            &self.anisette,
            Some((apple_id.clone(), password.clone())),
        )
        .await?;

        let user = api::try_icloud_login(self.dir.clone(), &self.config, &account).await?;

        *self.pending.lock().await = Pending {
            account: Some(account),
            creds: Some((apple_id, password)),
            user,
            ..Default::default()
        };

        self.advance(state).await
    }

    /// Resume the Apple ID a previous run saved, without re-prompting for a password.
    pub async fn login_saved(&self) -> Result<LoginStep> {
        let (account, state) =
            api::try_auth(self.dir.clone(), &self.config, &self.conn, &self.anisette, None).await?;

        let user = api::try_icloud_login(self.dir.clone(), &self.config, &account).await?;

        *self.pending.lock().await = Pending {
            account: Some(account),
            user,
            ..Default::default()
        };

        self.advance(state).await
    }

    /// Ask Apple to text a code instead, when the pushed device code cannot be reached.
    ///
    /// Re-enters the negotiation at `NeedsSMS2FA`, which [`Session::advance`] already knows how to
    /// resolve: fetch the trusted numbers, send straight away if there is only one, and otherwise
    /// come back asking which. Also the resend path once a text has been sent.
    ///
    /// The push watch is dropped because asking for a text abandons the device-code exchange —
    /// `send_2fa_sms` cancels the circle session it was subscribed for.
    pub async fn request_sms_code(&self) -> Result<LoginStep> {
        self.require_account().await?;
        self.pending.lock().await.aps_watch = None;
        self.advance(LoginState::NeedsSMS2FA).await
    }

    /// Pick which trusted number should receive the SMS code.
    pub async fn choose_sms_target(&self, target_id: u32) -> Result<LoginStep> {
        let account = self.require_account().await?;
        // Cancelling any in-flight device-code session is `send_2fa_sms`'s job, so hand it over.
        let circle = self.pending.lock().await.circle.take();
        let state = api::send_2fa_sms(circle, &account, target_id).await?;
        self.advance(state).await
    }

    /// Submit the six-digit code, from whichever channel it arrived on.
    ///
    /// The two verifications are entirely different exchanges — the device flow reads the answer
    /// off the push socket, the SMS flow posts it to Apple — but which one applies is knowable from
    /// state we already hold, so the UI does not need two buttons.
    pub async fn submit_code(&self, code: String) -> Result<LoginStep> {
        let account = self.require_account().await?;

        let sms_body = self.pending.lock().await.sms_body.clone();
        if let Some(body) = sms_body {
            let (state, user) = api::verify_2fa_sms(
                self.dir.clone(),
                &account,
                &self.anisette,
                &self.config,
                &body,
                code,
            )
            .await?;

            {
                let mut pending = self.pending.lock().await;
                pending.sms_body = None;
                if user.is_some() {
                    pending.user = user;
                }
            }

            return self.advance(state).await;
        }

        let (circle, watch) = {
            let mut pending = self.pending.lock().await;
            (pending.circle.take(), pending.aps_watch.take())
        };
        let (Some(mut circle), Some(mut watch)) = (circle, watch) else {
            return Err(ImError::msg("No verification is in progress"));
        };

        let result = api::verify_2fa(
            self.dir.clone(),
            &mut circle,
            &self.anisette,
            &self.config,
            &account,
            &mut watch,
            &self.idms,
            code,
        )
        .await;

        let (state, user) = match result {
            Ok(ok) => ok,
            Err(err) => {
                // Put the exchange back so a mistyped code can simply be retried.
                let mut pending = self.pending.lock().await;
                pending.circle = Some(circle);
                pending.aps_watch = Some(watch);
                return Err(err.into());
            }
        };

        if user.is_some() {
            self.pending.lock().await.user = user;
        }

        self.advance(state).await
    }

    /// Register with IDS, which is what actually makes the account able to send and receive.
    ///
    /// Returns the handles the account may send from. A `SupportAlert` from Apple is surfaced as an
    /// error carrying its text, since receiving one always means registration did not happen.
    pub async fn register(&self) -> Result<Vec<String>> {
        let user = self.pending.lock().await.user.clone();

        // Re-registering an existing install has no pending user, but does have saved ones.
        let users = match user {
            Some(user) => vec![user],
            None => api::restore_users(self.dir.clone())
                .ok_or_else(|| ImError::msg("Not signed in"))?,
        };

        let (registered, alert) = api::register_ids(
            self.dir.clone(),
            &self.config,
            &self.conn,
            &self.identity,
            users,
        )
        .await?;

        if let Some(alert) = alert {
            // `SupportAlert`'s Display already joins its title, body and any support URL.
            return Err(ImError::msg(alert.to_string()));
        }

        let Some(registered) = registered else {
            return Err(ImError::msg("Registration did not complete"));
        };

        let client =
            api::make_imclient(self.dir.clone(), &self.conn, &registered, &self.identity).await;
        let handles = api::get_handles(&client).await?;
        *self.client.lock().await = Some(client);

        Ok(handles)
    }

    /// The addresses this account can send from.
    pub async fn handles(&self) -> Result<Vec<String>> {
        let client = self.require_client().await?;
        Ok(api::get_handles(&client).await?)
    }

    /// Which of `targets` can receive iMessages — what decides blue versus green in the composer.
    pub async fn validate_targets(
        &self,
        targets: Vec<String>,
        sender: String,
    ) -> Result<Vec<String>> {
        let client = self.require_client().await?;
        Ok(api::validate_targets(&client, targets, sender).await?)
    }

    /// Force an IDS re-registration, e.g. after Apple invalidates the identity.
    pub async fn reregister(&self) -> Result<()> {
        let client = self.require_client().await?;
        api::do_reregister(&client).await?;
        Ok(())
    }
}
