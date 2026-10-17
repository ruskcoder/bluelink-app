//! Packet protection for QUIC pods.
//!
//! This module deliberately only implements crypto primitives. A pod packet can use Quinn's normal
//! packet number, ACK, loss, retransmission, and frame machinery as long as that machinery selects
//! these keys and the pod's connection IDs.

use aes::{
    Aes128,
    cipher::{BlockEncrypt, KeyInit, generic_array::GenericArray},
};
use bytes::BytesMut;
use hmac::{Hmac, Mac};
use sha2::Sha256;
use tracing::debug;

use super::{CryptoError, HeaderKey, KeyPair, Keys, PacketKey};
use crate::{Side, constant_time, shared::ConnectionId};

const SECRET_LEN: usize = 32;
const KEY_LEN: usize = 16;
const IV_LEN: usize = 12;
const TAG_LEN: usize = 6;

type HmacSha256 = Hmac<Sha256>;

/// Pod packet protection and routing identifiers for one endpoint.
pub struct PodKeys {
    keys: Keys,
    send_cid: ConnectionId,
    recv_cid: ConnectionId,
}

impl PodKeys {
    /// Packet protection keys, shaped exactly like Quinn's normal 1-RTT keys.
    pub fn keys(self) -> Keys {
        self.keys
    }

    /// Connection ID used on packets sent by this endpoint.
    pub fn send_cid(&self) -> ConnectionId {
        self.send_cid
    }

    /// Connection ID used to route packets received by this endpoint.
    pub fn recv_cid(&self) -> ConnectionId {
        self.recv_cid
    }
}

/// Construct the TLS exporter label used by Wireshark-style qpod keys.
///
/// For example, `label = b"IDS"` and `sender = b"client"` yields `b"IDS client"`.
pub fn exporter_label(label: &[u8], sender: &[u8]) -> Vec<u8> {
    let mut exporter_label = Vec::with_capacity(label.len() + 1 + sender.len());
    exporter_label.extend_from_slice(label);
    exporter_label.push(b' ');
    exporter_label.extend_from_slice(sender);
    exporter_label
}

/// Build pod packet protection keys from sender-specific TLS exporter secrets.
///
/// `client_secret` must come from the `<label> client` exporter and protects packets sent by the
/// client. `server_secret` must come from the `<label> server` exporter and protects packets sent by
/// the server. `client_cid` routes packets to the client and `server_cid` routes packets to the
/// server.
pub fn new(
    client_secret: &[u8; SECRET_LEN],
    server_secret: &[u8; SECRET_LEN],
    label: &[u8],
    client_cid: [u8; 4],
    server_cid: [u8; 4],
    side: Side,
) -> PodKeys {
    debug!(
        label = %String::from_utf8_lossy(label),
        client_cid = ?client_cid,
        server_cid = ?server_cid,
        side = ?side,
        "deriving pod keys"
    );
    let client = derive_keys(client_secret);
    let server = derive_keys(server_secret);
    let (local, remote, send_cid, recv_cid) = if side.is_client() {
        (
            client,
            server,
            ConnectionId::new(&server_cid),
            ConnectionId::new(&client_cid),
        )
    } else {
        (
            server,
            client,
            ConnectionId::new(&client_cid),
            ConnectionId::new(&server_cid),
        )
    };

    debug!(%send_cid, %recv_cid, "derived pod endpoint keys");
    PodKeys {
        keys: Keys {
            header: KeyPair {
                local: Box::new(local.header),
                remote: Box::new(remote.header),
            },
            packet: KeyPair {
                local: Box::new(local.packet),
                remote: Box::new(remote.packet),
            },
        },
        send_cid,
        recv_cid,
    }
}

fn derive_keys(secret: &[u8; SECRET_LEN]) -> DirectionKeys {
    debug!("deriving pod direction keys");
    let hp_key = hkdf_expand_label(secret, b"quic hp", &[], KEY_LEN);
    let packet_key = hkdf_expand_label(secret, b"quic key", &[], KEY_LEN);
    let iv: [u8; IV_LEN] = hkdf_expand_label(secret, b"quic iv", &[], IV_LEN)
        .try_into()
        .expect("HKDF output has requested IV length");
    let mac_key: [u8; KEY_LEN] = hkdf_expand_label(secret, b"qpod hmac", &[], KEY_LEN)
        .try_into()
        .expect("HKDF output has requested MAC key length");
    debug!(
        hp_key = ?hp_key,
        packet_key = ?packet_key,
        iv = ?iv,
        mac_key = ?mac_key,
        "derived pod key material"
    );

    DirectionKeys {
        header: PodHeaderKey {
            key: Aes128::new(GenericArray::from_slice(&hp_key)),
            key_bytes: hp_key
                .try_into()
                .expect("HKDF output has requested header key length"),
        },
        packet: PodPacketKey {
            key: Aes128::new(GenericArray::from_slice(&packet_key)),
            key_bytes: packet_key
                .try_into()
                .expect("HKDF output has requested packet key length"),
            iv,
            mac_key,
        },
    }
}

fn hkdf_expand_label(
    secret: &[u8; SECRET_LEN],
    label: &[u8],
    context: &[u8],
    len: usize,
) -> Vec<u8> {
    let hkdf = hkdf::Hkdf::<Sha256>::from_prk(secret).expect("SHA-256 accepts 32-byte PRKs");
    let mut info = Vec::with_capacity(2 + 1 + 6 + label.len() + 1 + context.len());
    info.extend_from_slice(&(len as u16).to_be_bytes());
    info.push((6 + label.len()) as u8);
    info.extend_from_slice(b"tls13 ");
    info.extend_from_slice(label);
    info.push(context.len() as u8);
    info.extend_from_slice(context);

    let mut out = vec![0; len];
    hkdf.expand(&info, &mut out)
        .expect("valid HKDF output length");
    out
}

struct DirectionKeys {
    header: PodHeaderKey,
    packet: PodPacketKey,
}

struct PodHeaderKey {
    key: Aes128,
    key_bytes: [u8; KEY_LEN],
}

impl HeaderKey for PodHeaderKey {
    fn decrypt(&self, pn_offset: usize, packet: &mut [u8]) {
        let first_before = packet[0];
        let pn_before = packet_number_bytes(pn_offset, packet);
        // debug!(
        //     pn_offset,
        //     hp_key = ?self.key_bytes,
        //     "computing pod header protection mask for decrypt"
        // );
        let mask = header_mask(&self.key, pn_offset, packet);
        let first_mask = if packet[0] & 0x80 != 0 { 0x0f } else { 0x1f };
        packet[0] ^= mask[0] & first_mask;
        apply_packet_number_mask(&mask, pn_offset, packet);
        // debug!(
        //     pn_offset,
        //     first_before,
        //     first_after = packet[0],
        //     pn_before = ?pn_before,
        //     pn_after = ?packet_number_bytes(pn_offset, packet),
        //     mask = ?&mask[..5],
        //     "pod header protection removed"
        // );
    }

    fn encrypt(&self, pn_offset: usize, packet: &mut [u8]) {
        let first_before = packet[0];
        let pn_before = packet_number_bytes(pn_offset, packet);
        // debug!(
        //     pn_offset,
        //     hp_key = ?self.key_bytes,
        //     "computing pod header protection mask for encrypt"
        // );
        let mask = header_mask(&self.key, pn_offset, packet);
        apply_packet_number_mask(&mask, pn_offset, packet);
        let first_mask = if packet[0] & 0x80 != 0 { 0x0f } else { 0x1f };
        packet[0] ^= mask[0] & first_mask;
        // debug!(
        //     pn_offset,
        //     first_before,
        //     first_after = packet[0],
        //     pn_before = ?pn_before,
        //     pn_after = ?packet_number_bytes(pn_offset, packet),
        //     mask = ?&mask[..5],
        //     "pod header protection applied"
        // );
    }

    fn sample_size(&self) -> usize {
        16
    }
}

fn header_mask(
    key: &Aes128,
    pn_offset: usize,
    packet: &[u8],
) -> GenericArray<u8, aes::cipher::consts::U16> {
    let mut sample = GenericArray::clone_from_slice(&packet[pn_offset + 4..pn_offset + 20]);
    key.encrypt_block(&mut sample);
    sample
}

fn apply_packet_number_mask(mask: &[u8], pn_offset: usize, packet: &mut [u8]) {
    let pn_len = usize::from(packet[0] & 0x03) + 1;
    for i in 0..pn_len {
        packet[pn_offset + i] ^= mask[1 + i];
    }
}

fn packet_number_bytes(pn_offset: usize, packet: &[u8]) -> Vec<u8> {
    let pn_len = usize::from(packet[0] & 0x03) + 1;
    packet[pn_offset..pn_offset + pn_len].to_vec()
}

struct PodPacketKey {
    key: Aes128,
    key_bytes: [u8; KEY_LEN],
    iv: [u8; IV_LEN],
    mac_key: [u8; KEY_LEN],
}

impl PacketKey for PodPacketKey {
    fn encrypt(&self, packet: u64, buf: &mut [u8], header_len: usize) {
        let tag_start = buf.len() - TAG_LEN;
        // debug!(
        //     packet,
        //     header_len,
        //     payload_len = tag_start.saturating_sub(header_len),
        //     tag_len = TAG_LEN,
        //     packet_key = ?self.key_bytes,
        //     iv = ?self.iv,
        //     mac_key = ?self.mac_key,
        //     nonce = ?nonce(&self.iv, packet),
        //     "encrypting pod packet"
        // );
        apply_ctr(
            &self.key,
            nonce(&self.iv, packet),
            &mut buf[header_len..tag_start],
        );
        let tag = tag(&self.mac_key, header_len, &buf[..tag_start]);
        buf[tag_start..].copy_from_slice(&tag[..TAG_LEN]);
        // debug!(packet, tag = ?&buf[tag_start..], "pod packet tag written");
    }

    fn decrypt(
        &self,
        packet: u64,
        header: &[u8],
        payload: &mut BytesMut,
    ) -> Result<(), CryptoError> {
        if payload.len() < TAG_LEN {
            debug!(
                packet,
                payload_len = payload.len(),
                tag_len = TAG_LEN,
                "pod packet too short for tag"
            );
            return Err(CryptoError);
        }
        let tag_start = payload.len() - TAG_LEN;
        // debug!(
        //     packet,
        //     header_len = header.len(),
        //     cipher_len = tag_start,
        //     tag_len = TAG_LEN,
        //     packet_key = ?self.key_bytes,
        //     iv = ?self.iv,
        //     mac_key = ?self.mac_key,
        //     nonce = ?nonce(&self.iv, packet),
        //     "decrypting pod packet"
        // );
        let mut authenticated = Vec::with_capacity(header.len() + tag_start);
        authenticated.extend_from_slice(header);
        authenticated.extend_from_slice(&payload[..tag_start]);
        let expected = tag(&self.mac_key, header.len(), &authenticated);
        if !constant_time::eq(&expected[..TAG_LEN], &payload[tag_start..]) {
            debug!(
                packet,
                expected = ?&expected[..TAG_LEN],
                actual = ?&payload[tag_start..],
                "pod packet tag mismatch"
            );
            return Err(CryptoError);
        }

        payload.truncate(tag_start);
        apply_ctr(&self.key, nonce(&self.iv, packet), payload);
        // debug!(packet, plain_len = payload.len(), "pod packet decrypted");
        Ok(())
    }

    fn tag_len(&self) -> usize {
        TAG_LEN
    }

    fn confidentiality_limit(&self) -> u64 {
        1 << 62
    }

    fn integrity_limit(&self) -> u64 {
        1 << 52
    }
}

fn nonce(iv: &[u8; IV_LEN], packet: u64) -> [u8; IV_LEN] {
    let mut nonce = *iv;
    for (dst, src) in nonce[IV_LEN - 8..].iter_mut().zip(packet.to_be_bytes()) {
        *dst ^= src;
    }
    nonce
}

fn apply_ctr(key: &Aes128, nonce: [u8; IV_LEN], buf: &mut [u8]) {
    for (block_index, chunk) in buf.chunks_mut(16).enumerate() {
        let mut block = [0; 16];
        block[..IV_LEN].copy_from_slice(&nonce);
        block[IV_LEN..].copy_from_slice(&(block_index as u32).to_be_bytes());
        let mut stream = GenericArray::clone_from_slice(&block);
        key.encrypt_block(&mut stream);
        for (byte, mask) in chunk.iter_mut().zip(stream.iter()) {
            *byte ^= mask;
        }
    }
}

fn tag(key: &[u8; KEY_LEN], header_len: usize, data: &[u8]) -> [u8; 32] {
    let hmac_prefix = u8::try_from(header_len).expect("QPOD header length fits in one byte");
    let mut mac = <HmacSha256 as Mac>::new_from_slice(key).expect("HMAC accepts any key length");
    mac.update(&[hmac_prefix]);
    mac.update(data);
    mac.finalize().into_bytes().into()
}
