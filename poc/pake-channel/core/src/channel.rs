//! Noise_KK channel over the pinned identities from pairing.
//!
//! ```text
//! HS1 K->H  "KHC2" 10 | registration_id[16] | noise msg1 (48 B, empty payload)
//! HS2 H->K  "KHC2" 11 | session_id[16]      | noise msg2 (48 B, empty payload)
//! REC both  "KHC2" 20 | session_id[16]      | noise transport message (16..=65535 B)
//! ```
//!
//! Prologue = NOISE_PROLOGUE_LABEL || registration_id, so the cleartext
//! registration id in HS1 is authenticated by the handshake. Handshake
//! payloads must be empty. Transport messages use snow's stateful
//! `TransportState` (implicit, strictly increasing nonces).
//!
//! Error semantics: every error from `seal`/`open` (wrong magic or type,
//! wrong session id, bad length, authentication failure, invalid
//! application record after successful authentication, use after close)
//! marks the channel dead before returning. A dead channel never touches
//! its CipherStates again, so neither a resynchronised continuation nor a
//! nonce reuse is possible; the only way forward is a new handshake.

use crate::record::AppRecord;
use crate::{noise_prologue, random_id, validate_public_key, Error, Identity, ID_LEN, KEY_LEN, NOISE_PARAMS};

pub const MAGIC: &[u8; 4] = b"KHC2";
pub const NOISE_MAX: usize = 65535;
pub const TAG_LEN: usize = 16;
pub const MAX_PLAINTEXT: usize = NOISE_MAX - TAG_LEN;
pub const HS_MSG_LEN: usize = 48;
pub const REC_HEADER: usize = 5 + ID_LEN;
pub const HS_LEN: usize = REC_HEADER + HS_MSG_LEN;
pub const MAX_RECORD: usize = REC_HEADER + NOISE_MAX;

fn builder<'a>(local: &'a Identity, remote: &'a [u8; KEY_LEN], prologue: &'a [u8]) -> Result<snow::Builder<'a>, Error> {
    snow::Builder::new(NOISE_PARAMS.parse().map_err(|_| Error::Crypto)?)
        .local_private_key(&local.private[..])
        .and_then(|b| b.remote_public_key(remote))
        .and_then(|b| b.prologue(prologue))
        .map_err(|_| Error::Crypto)
}

fn header(kind: u8, id: &[u8; ID_LEN], cap: usize) -> Vec<u8> {
    let mut out = Vec::with_capacity(REC_HEADER + cap);
    out.extend_from_slice(MAGIC);
    out.push(kind);
    out.extend_from_slice(id);
    out
}

/// Header parse shared by both sides; also used by the helper for routing.
pub fn split(msg: &[u8], kind: u8) -> Result<([u8; ID_LEN], &[u8]), Error> {
    if msg.len() < REC_HEADER || &msg[..4] != MAGIC || msg[4] != kind {
        return Err(Error::Malformed);
    }
    let mut id = [0u8; ID_LEN];
    id.copy_from_slice(&msg[5..REC_HEADER]);
    Ok((id, &msg[REC_HEADER..]))
}

/// An established, mutually authenticated session.
pub struct Channel {
    session_id: [u8; ID_LEN],
    transport: Option<snow::TransportState>,
}

impl Channel {
    pub fn session_id(&self) -> [u8; ID_LEN] {
        self.session_id
    }

    pub fn is_dead(&self) -> bool {
        self.transport.is_none()
    }

    /// Terminal close: drops both CipherStates.
    pub fn close(&mut self) {
        self.transport = None;
    }

    fn kill<T>(&mut self, e: Error) -> Result<T, Error> {
        self.transport = None;
        Err(e)
    }

    pub fn seal(&mut self, plaintext: &[u8]) -> Result<Vec<u8>, Error> {
        if self.transport.is_none() {
            return Err(Error::WrongState);
        }
        if plaintext.len() > MAX_PLAINTEXT {
            return self.kill(Error::TooLarge);
        }
        let mut out = header(0x20, &self.session_id, plaintext.len() + TAG_LEN);
        out.resize(REC_HEADER + plaintext.len() + TAG_LEN, 0);
        let t = self.transport.as_mut().unwrap();
        match t.write_message(plaintext, &mut out[REC_HEADER..]) {
            Ok(n) if n == plaintext.len() + TAG_LEN => Ok(out),
            _ => self.kill(Error::Crypto),
        }
    }

    pub fn open(&mut self, record: &[u8]) -> Result<Vec<u8>, Error> {
        if self.transport.is_none() {
            return Err(Error::WrongState);
        }
        if record.len() > MAX_RECORD {
            return self.kill(Error::TooLarge);
        }
        let (sid, ct) = match split(record, 0x20) {
            Ok(v) => v,
            Err(e) => return self.kill(e),
        };
        if sid != self.session_id {
            return self.kill(Error::Malformed);
        }
        if ct.len() < TAG_LEN {
            return self.kill(Error::Malformed);
        }
        let mut pt = vec![0u8; ct.len() - TAG_LEN];
        let t = self.transport.as_mut().unwrap();
        match t.read_message(ct, &mut pt) {
            Ok(n) if n == pt.len() => Ok(pt),
            _ => self.kill(Error::AuthFailed),
        }
    }

    pub fn seal_app(&mut self, rec: &AppRecord) -> Result<Vec<u8>, Error> {
        if self.transport.is_none() {
            return Err(Error::WrongState);
        }
        match rec.encode() {
            Ok(b) => self.seal(&b),
            Err(e) => self.kill(e),
        }
    }

    /// Authenticated but undecodable application records are terminal too.
    pub fn open_app(&mut self, record: &[u8]) -> Result<AppRecord, Error> {
        let pt = self.open(record)?;
        match AppRecord::decode(&pt) {
            Ok(r) => Ok(r),
            Err(e) => self.kill(e),
        }
    }
}

/// Kadri side: starts a handshake to the pinned helper key.
pub struct Initiator {
    hs: Option<snow::HandshakeState>,
}

impl Initiator {
    pub fn start(local: &Identity, helper_public: &[u8; KEY_LEN], registration_id: &[u8; ID_LEN]) -> Result<(Self, Vec<u8>), Error> {
        validate_public_key(helper_public)?;
        let prologue = noise_prologue(registration_id);
        let mut hs = builder(local, helper_public, &prologue)?.build_initiator().map_err(|_| Error::Crypto)?;
        let mut out = header(0x10, registration_id, HS_MSG_LEN);
        out.resize(HS_LEN, 0);
        let n = hs.write_message(&[], &mut out[REC_HEADER..]).map_err(|_| Error::Crypto)?;
        if n != HS_MSG_LEN {
            return Err(Error::Crypto);
        }
        Ok((Self { hs: Some(hs) }, out))
    }

    /// Single use: the handshake state is consumed whatever the outcome.
    pub fn finish(&mut self, hs2: &[u8]) -> Result<Channel, Error> {
        let mut hs = self.hs.take().ok_or(Error::WrongState)?;
        if hs2.len() != HS_LEN {
            return Err(Error::Malformed);
        }
        let (session_id, body) = split(hs2, 0x11)?;
        let mut payload = [0u8; HS_MSG_LEN];
        let n = hs.read_message(body, &mut payload).map_err(|_| Error::AuthFailed)?;
        if n != 0 || !hs.is_handshake_finished() {
            return Err(Error::AuthFailed);
        }
        let transport = hs.into_transport_mode().map_err(|_| Error::Crypto)?;
        Ok(Channel { session_id, transport: Some(transport) })
    }
}

/// Helper side: answers a handshake for a registration it has pinned.
pub fn respond(
    local: &Identity,
    hs1: &[u8],
    lookup: impl FnOnce(&[u8; ID_LEN]) -> Option<[u8; KEY_LEN]>,
) -> Result<(Channel, Vec<u8>), Error> {
    if hs1.len() != HS_LEN {
        return Err(Error::Malformed);
    }
    let (registration_id, body) = split(hs1, 0x10)?;
    let kadri_public = lookup(&registration_id).ok_or(Error::AuthFailed)?;
    validate_public_key(&kadri_public)?;
    let prologue = noise_prologue(&registration_id);
    let mut hs = builder(local, &kadri_public, &prologue)?.build_responder().map_err(|_| Error::Crypto)?;
    let mut payload = [0u8; HS_MSG_LEN];
    let n = hs.read_message(body, &mut payload).map_err(|_| Error::AuthFailed)?;
    if n != 0 {
        return Err(Error::AuthFailed);
    }
    let session_id = random_id()?;
    let mut out = header(0x11, &session_id, HS_MSG_LEN);
    out.resize(HS_LEN, 0);
    let n = hs.write_message(&[], &mut out[REC_HEADER..]).map_err(|_| Error::Crypto)?;
    if n != HS_MSG_LEN || !hs.is_handshake_finished() {
        return Err(Error::Crypto);
    }
    let transport = hs.into_transport_mode().map_err(|_| Error::Crypto)?;
    Ok((Channel { session_id, transport: Some(transport) }, out))
}
