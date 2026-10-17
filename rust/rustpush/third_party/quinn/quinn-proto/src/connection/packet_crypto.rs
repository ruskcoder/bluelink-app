use std::fmt;

use tracing::{debug, trace};

use crate::Instant;
use crate::connection::spaces::PacketSpace;
use crate::crypto::{HeaderKey, KeyPair, PacketKey};
use crate::packet::{Packet, PartialDecode, SpaceId};
use crate::token::ResetToken;
use crate::{RESET_TOKEN_SIZE, TransportError};

/// Removes header protection of a packet, or returns `None` if the packet was dropped
pub(super) fn unprotect_header(
    partial_decode: PartialDecode,
    spaces: &[PacketSpace; 3],
    zero_rtt_crypto: Option<&ZeroRttCrypto>,
    stateless_reset_token: Option<ResetToken>,
) -> Option<UnprotectHeaderResult> {
    let header_crypto = if partial_decode.is_0rtt() {
        if let Some(crypto) = zero_rtt_crypto {
            Some(&*crypto.header)
        } else {
            debug!("dropping unexpected 0-RTT packet");
            return None;
        }
    } else if let Some(space) = partial_decode.space() {
        if let Some(ref crypto) = spaces[space].crypto {
            Some(&*crypto.header.remote)
        } else {
            debug!(
                "discarding unexpected {:?} packet ({} bytes)",
                space,
                partial_decode.len(),
            );
            return None;
        }
    } else {
        // Unprotected packet
        None
    };

    let packet = partial_decode.data();
    let stateless_reset = packet.len() >= RESET_TOKEN_SIZE + 5
        && stateless_reset_token.as_deref() == Some(&packet[packet.len() - RESET_TOKEN_SIZE..]);

    match partial_decode.finish(header_crypto) {
        Ok(packet) => Some(UnprotectHeaderResult {
            packet: Some(packet),
            stateless_reset,
        }),
        Err(_) if stateless_reset => Some(UnprotectHeaderResult {
            packet: None,
            stateless_reset: true,
        }),
        Err(e) => {
            trace!("unable to complete packet decoding: {}", e);
            None
        }
    }
}

pub(super) struct UnprotectHeaderResult {
    /// The packet with the now unprotected header (`None` in the case of stateless reset packets
    /// that fail to be decoded)
    pub(super) packet: Option<Packet>,
    /// Whether the packet was a stateless reset packet
    pub(super) stateless_reset: bool,
}

/// Decrypts a packet's body in-place
pub(super) fn decrypt_packet_body(
    packet: &mut Packet,
    spaces: &[PacketSpace; 3],
    zero_rtt_crypto: Option<&ZeroRttCrypto>,
    conn_key_phase: bool,
    prev_crypto: Option<&PrevCrypto>,
    next_crypto: Option<&KeyPair<Box<dyn PacketKey>>>,
) -> Result<Option<DecryptPacketResult>, Option<TransportError>> {
    if !packet.header.is_protected() {
        // Unprotected packets also don't have packet numbers
        return Ok(None);
    }
    let space = packet.header.space();
    let rx_packet = spaces[space].rx_packet;
    let number = packet.header.number().ok_or(None)?.expand(rx_packet + 1);
    let packet_key_phase = packet.header.key_phase();
    // debug!(
    //     ?space,
    //     rx_packet,
    //     expected_packet = rx_packet + 1,
    //     number,
    //     packet_key_phase,
    //     conn_key_phase,
    //     is_0rtt = packet.header.is_0rtt(),
    //     "decrypting packet body"
    // );

    let mut crypto_update = false;
    let crypto = if packet.header.is_0rtt() {
        // debug!(number, "using 0-RTT packet keys");
        &zero_rtt_crypto.unwrap().packet
    } else if packet_key_phase == conn_key_phase || space != SpaceId::Data {
        // debug!(number, ?space, "using current packet keys");
        &spaces[space].crypto.as_ref().unwrap().packet.remote
    } else if let Some(prev) = prev_crypto.and_then(|crypto| {
        // If this packet comes prior to acknowledgment of the key update by the peer,
        if crypto.end_packet.map_or(true, |(pn, _)| number < pn) {
            // use the previous keys.
            Some(crypto)
        } else {
            // Otherwise, this must be a remotely-initiated key update, so fall through to the
            // final case.
            None
        }
    }) {
        debug!(
            number,
            prev_end_packet = ?prev.end_packet,
            "using previous packet keys after key phase mismatch"
        );
        &prev.crypto.remote
    } else {
        // We're in the Data space with a key phase mismatch and either there is no locally
        // initiated key update or the locally initiated key update was acknowledged by a
        // lower-numbered packet. The key phase mismatch must therefore represent a new
        // remotely-initiated key update.
        crypto_update = true;
        debug!(
            number,
            rx_packet,
            packet_key_phase,
            conn_key_phase,
            prev_crypto = prev_crypto.is_some(),
            next_crypto = next_crypto.is_some(),
            "packet key phase mismatch interpreted as incoming key update"
        );
        &next_crypto.unwrap().remote
    };

    crypto
        .decrypt(number, &packet.header_data, &mut packet.payload)
        .map_err(|_| {
            debug!(
                number,
                ?space,
                packet_key_phase,
                conn_key_phase,
                crypto_update,
                header_len = packet.header_data.len(),
                payload_len = packet.payload.len(),
                "decryption failed"
            );
            None
        })?;
    // debug!(
    //     number,
    //     ?space,
    //     payload_len = packet.payload.len(),
    //     payload = %Hex(&packet.payload),
    //     "decrypted packet body"
    // );

    if !packet.reserved_bits_valid() {
        return Err(Some(TransportError::PROTOCOL_VIOLATION(
            "reserved bits set",
        )));
    }

    let mut outgoing_key_update_acked = false;
    if let Some(prev) = prev_crypto {
        if prev.end_packet.is_none() && packet_key_phase == conn_key_phase {
            outgoing_key_update_acked = true;
        }
    }

    if crypto_update {
        // Validate incoming key update
        if number <= rx_packet || prev_crypto.is_some_and(|x| x.update_unacked) {
            return Err(Some(TransportError::KEY_UPDATE_ERROR("")));
        }
    }

    Ok(Some(DecryptPacketResult {
        number,
        outgoing_key_update_acked,
        incoming_key_update: crypto_update,
    }))
}

struct Hex<'a>(&'a [u8]);

impl fmt::Display for Hex<'_> {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        for byte in self.0 {
            write!(f, "{byte:02x}")?;
        }
        Ok(())
    }
}

pub(super) struct DecryptPacketResult {
    /// The packet number
    pub(super) number: u64,
    /// Whether a locally initiated key update has been acknowledged by the peer
    pub(super) outgoing_key_update_acked: bool,
    /// Whether the peer has initiated a key update
    pub(super) incoming_key_update: bool,
}

pub(super) struct PrevCrypto {
    /// The keys used for the previous key phase, temporarily retained to decrypt packets sent by
    /// the peer prior to its own key update.
    pub(super) crypto: KeyPair<Box<dyn PacketKey>>,
    /// The incoming packet that ends the interval for which these keys are applicable, and the time
    /// of its receipt.
    ///
    /// Incoming packets should be decrypted using these keys iff this is `None` or their packet
    /// number is lower. `None` indicates that we have not yet received a packet using newer keys,
    /// which implies that the update was locally initiated.
    pub(super) end_packet: Option<(u64, Instant)>,
    /// Whether the following key phase is from a remotely initiated update that we haven't acked
    pub(super) update_unacked: bool,
}

pub(super) struct ZeroRttCrypto {
    pub(super) header: Box<dyn HeaderKey>,
    pub(super) packet: Box<dyn PacketKey>,
}
