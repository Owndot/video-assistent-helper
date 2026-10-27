//! Noise_KK channel over the pinned identities from pairing.
//!
//! ```text
//! HS1 K->H  "KHC1" 10 | registration_id[16] | noise msg1 (48 B, empty payload)
//! HS2 H->K  "KHC1" 11 | session_id[16]      | noise msg2 (48 B, empty payload)
//! REC both  "KHC1" 20 | session_id[16]      | noise transport message (<= 65535 B)
//! ```
//!
//! Handshake payloads are empty: no application byte is sent before the
//! handshake is finished on the sending side. Transport messages use snow's
//! stateful `TransportState`, so nonces are implicit and strictly ordered.

use crate::{random_id, Error, Identity, ID_LEN, KEY_LEN, NOISE_PARAMS, NOISE_PROLOGUE};

pub const MAGIC: &[u8; 4] = b"KHC1";
pub const NOISE_MAX: usize = 65535;
pub const TAG_LEN: usize = 16;
pub const MAX_PLAINTEXT: usize = NOISE_MAX - TAG_LEN;
pub const HS_LEN: usize = 5 + ID_LEN + 48;
pub const REC_HEADER: usize = 5 + ID_LEN;

fn builder<'a>(local: &'a Identity, remote: &'a [u8; KEY_LEN]) -> Result<snow::Builder<'a>, Error> {
    snow::Builder::new(NOISE_PARAMS.parse().map_err(|_| Error::Crypto)?)
        .local_private_key(&local.private[..])
        .and_then(|b| b.remote_public_key(remote))
        .and_then(|b| b.prologue(NOISE_PROLOGUE))
        .map_err(|_| Error::Crypto)
}

fn header(kind: u8, id: &[u8; ID_LEN], cap: usize) -> Vec<u8> {
    let mut out = Vec::with_capacity(REC_HEADER + cap);
    out.extend_from_slice(MAGIC);
    out.push(kind);
    out.extend_from_slice(id);
    out
}

fn split(msg: &[u8], kind: u8) -> Result<([u8; ID_LEN], &[u8]), Error> {
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
    transport: snow::TransportState,
    dead: bool,
}

impl Channel {
    pub fn session_id(&self) -> [u8; ID_LEN] {
        self.session_id
    }

    pub fn seal(&mut self, plaintext: &[u8]) -> Result<Vec<u8>, Error> {
        if self.dead {
            return Err(Error::WrongState);
        }
        if plaintext.len() > MAX_PLAINTEXT {
            return Err(Error::TooLarge);
        }
        let mut out = header(0x20, &self.session_id, plaintext.len() + TAG_LEN);
        out.resize(REC_HEADER + plaintext.len() + TAG_LEN, 0);
        let n = self.transport.write_message(plaintext, &mut out[REC_HEADER..]).map_err(|_| Error::Crypto)?;
        out.truncate(REC_HEADER + n);
        Ok(out)
    }

    /// Any authentication failure kills the session; there is no resync.
    pub fn open(&mut self, record: &[u8]) -> Result<Vec<u8>, Error> {
        if self.dead {
            return Err(Error::WrongState);
        }
        let (sid, ct) = split(record, 0x20)?;
        if sid != self.session_id {
            return Err(Error::Malformed);
        }
        if ct.len() > NOISE_MAX || ct.len() < TAG_LEN {
            self.dead = true;
            return Err(Error::TooLarge);
        }
        let mut pt = vec![0u8; ct.len() - TAG_LEN];
        match self.transport.read_message(ct, &mut pt) {
            Ok(n) => {
                pt.truncate(n);
                Ok(pt)
            }
            Err(_) => {
                self.dead = true;
                Err(Error::AuthFailed)
            }
        }
    }
}

/// Kadri side: starts a handshake to the pinned helper key.
pub struct Initiator {
    hs: snow::HandshakeState,
}

impl Initiator {
    pub fn start(local: &Identity, helper_public: &[u8; KEY_LEN], registration_id: &[u8; ID_LEN]) -> Result<(Self, Vec<u8>), Error> {
        let mut hs = builder(local, helper_public)?.build_initiator().map_err(|_| Error::Crypto)?;
        let mut out = header(0x10, registration_id, 48);
        out.resize(HS_LEN, 0);
        let n = hs.write_message(&[], &mut out[REC_HEADER..]).map_err(|_| Error::Crypto)?;
        if n != 48 {
            return Err(Error::Crypto);
        }
        Ok((Self { hs }, out))
    }

    pub fn finish(mut self, hs2: &[u8]) -> Result<Channel, Error> {
        let (session_id, body) = split(hs2, 0x11)?;
        if hs2.len() != HS_LEN {
            return Err(Error::Malformed);
        }
        let mut payload = [0u8; 64];
        let n = self.hs.read_message(body, &mut payload).map_err(|_| Error::AuthFailed)?;
        if n != 0 || !self.hs.is_handshake_finished() {
            return Err(Error::AuthFailed);
        }
        let transport = self.hs.into_transport_mode().map_err(|_| Error::Crypto)?;
        Ok(Channel { session_id, transport, dead: false })
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
    let mut hs = builder(local, &kadri_public)?.build_responder().map_err(|_| Error::Crypto)?;
    let mut payload = [0u8; 64];
    let n = hs.read_message(body, &mut payload).map_err(|_| Error::AuthFailed)?;
    if n != 0 {
        return Err(Error::AuthFailed);
    }
    let session_id = random_id()?;
    let mut out = header(0x11, &session_id, 48);
    out.resize(HS_LEN, 0);
    let n = hs.write_message(&[], &mut out[REC_HEADER..]).map_err(|_| Error::Crypto)?;
    if n != 48 || !hs.is_handshake_finished() {
        return Err(Error::Crypto);
    }
    let transport = hs.into_transport_mode().map_err(|_| Error::Crypto)?;
    Ok((Channel { session_id, transport, dead: false }, out))
}
