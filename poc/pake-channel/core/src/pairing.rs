//! One-time-code pairing with RFC 9382 SPAKE2.
//!
//! Roles are fixed: Kadri is RFC party A, the helper is RFC party B.
//!
//! ```text
//! M0 K->H  "KHP1" 01 | kadri_pub[32]                          (37 B)
//! M1 H->K  "KHP1" 02 | pairing_id[16] | helper_pub[32] | pB[65] (118 B)
//! M2 K->H  "KHP1" 03 | pairing_id[16] | pA[65] | cA[32]        (118 B)
//! M3 H->K  "KHP1" 04 | pairing_id[16] | cB[32]                 (53 B)
//! ```
//!
//! The helper reveals cB only after verifying cA. Each code allows exactly one
//! attempt on each side; any failure burns it.

use crate::{random_id, Error, ID_LEN, ID_PREFIX_HELPER, ID_PREFIX_KADRI, KEY_LEN, PAKE_AAD};
use pakery_core::crypto::{CpaceGroup, Ksf};
use pakery_crypto::ksf::{Argon2Params, Argon2idKsfWithParams};
use pakery_crypto::{P256Group, Spake2P256};
use pakery_spake2::{PartyA, PartyB, PartyBState, Spake2Output};
use zeroize::Zeroizing;

type Scalar = <P256Group as CpaceGroup>::Scalar;

pub const MAGIC: &[u8; 4] = b"KHP1";
pub const SHARE_LEN: usize = 65;
pub const CONF_LEN: usize = 32;
pub const CODE_DIGITS: usize = 8;
pub const M0_LEN: usize = 5 + KEY_LEN;
pub const M1_LEN: usize = 5 + ID_LEN + KEY_LEN + SHARE_LEN;
pub const M2_LEN: usize = 5 + ID_LEN + SHARE_LEN + CONF_LEN;
pub const M3_LEN: usize = 5 + ID_LEN + CONF_LEN;

/// RFC 9382 §3.2 asks for a memory-hard function before reduction mod p.
/// The code is single-use, so this cost only has to be moderate.
pub struct PocArgon2;
impl Argon2Params for PocArgon2 {
    const M_COST: u32 = 8 * 1024; // 8 MiB
    const T_COST: u32 = 2;
    const P_COST: u32 = 1;
}

/// Accepts exactly CODE_DIGITS ASCII digits, nothing else.
pub fn password_scalar(code: &str) -> Result<Scalar, Error> {
    let b = code.as_bytes();
    if b.len() != CODE_DIGITS || !b.iter().all(u8::is_ascii_digit) {
        return Err(Error::Malformed);
    }
    let wide = Argon2idKsfWithParams::<PocArgon2>::stretch(b, 64).map_err(|_| Error::Crypto)?;
    P256Group::scalar_from_wide_bytes(&wide).map_err(|_| Error::Crypto)
}

pub fn identity_kadri(public: &[u8; KEY_LEN]) -> Vec<u8> {
    [ID_PREFIX_KADRI, &public[..]].concat()
}

pub fn identity_helper(public: &[u8; KEY_LEN]) -> Vec<u8> {
    [ID_PREFIX_HELPER, &public[..]].concat()
}

fn rng() -> rand_core::UnwrapErr<getrandom04::SysRng> {
    rand_core::UnwrapErr(getrandom04::SysRng)
}

fn frame(kind: u8, parts: &[&[u8]]) -> Vec<u8> {
    let mut out = Vec::with_capacity(5 + parts.iter().map(|p| p.len()).sum::<usize>());
    out.extend_from_slice(MAGIC);
    out.push(kind);
    for p in parts {
        out.extend_from_slice(p);
    }
    out
}

fn parse(msg: &[u8], kind: u8, len: usize) -> Result<&[u8], Error> {
    if msg.len() != len || &msg[..4] != MAGIC || msg[4] != kind {
        return Err(Error::Malformed);
    }
    Ok(&msg[5..])
}

fn take<const N: usize>(b: &[u8], at: usize) -> [u8; N] {
    let mut out = [0u8; N];
    out.copy_from_slice(&b[at..at + N]);
    out
}

/// Result of a completed pairing on either side: what gets pinned.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Pinned {
    pub registration_id: [u8; ID_LEN],
    pub peer_public: [u8; KEY_LEN],
}

// ---------------------------------------------------------------- Kadri (A)

enum KadriState {
    SentHello,
    SentConfirm { pairing_id: [u8; ID_LEN], helper_public: [u8; KEY_LEN], output: Spake2Output },
    Done,
    Failed,
}

/// Kadri side. Created after the user typed the code into the options page.
pub struct KadriPairing {
    w: Option<Zeroizing<Scalar>>,
    kadri_public: [u8; KEY_LEN],
    deadline_ms: u64,
    state: KadriState,
}

impl KadriPairing {
    pub fn start(code: &str, kadri_public: [u8; KEY_LEN], now_ms: u64, ttl_ms: u64) -> Result<(Self, Vec<u8>), Error> {
        let w = Zeroizing::new(password_scalar(code)?);
        let m0 = frame(1, &[&kadri_public]);
        Ok((Self { w: Some(w), kadri_public, deadline_ms: now_ms.saturating_add(ttl_ms), state: KadriState::SentHello }, m0))
    }

    fn fail(&mut self, e: Error) -> Error {
        self.state = KadriState::Failed;
        self.w = None;
        e
    }

    /// Processes M1, returns M2. Any error burns this attempt.
    pub fn on_m1(&mut self, m1: &[u8], now_ms: u64) -> Result<Vec<u8>, Error> {
        if !matches!(self.state, KadriState::SentHello) {
            return Err(self.fail(Error::WrongState));
        }
        if now_ms > self.deadline_ms {
            return Err(self.fail(Error::Expired));
        }
        let b = match parse(m1, 2, M1_LEN) { Ok(b) => b, Err(e) => return Err(self.fail(e)) };
        let pairing_id: [u8; ID_LEN] = take(b, 0);
        let helper_public: [u8; KEY_LEN] = take(b, ID_LEN);
        let pb = &b[ID_LEN + KEY_LEN..];
        let w = self.w.take().ok_or(Error::WrongState)?;
        let ida = identity_kadri(&self.kadri_public);
        let idb = identity_helper(&helper_public);
        let (pa, st) = match PartyA::<Spake2P256>::start(&w, &ida, &idb, PAKE_AAD, &mut rng()) {
            Ok(v) => v,
            Err(_) => return Err(self.fail(Error::Crypto)),
        };
        let output = match st.finish(pb) { Ok(o) => o, Err(_) => return Err(self.fail(Error::Malformed)) };
        let m2 = frame(3, &[&pairing_id, &pa, &output.confirmation_mac]);
        self.state = KadriState::SentConfirm { pairing_id, helper_public, output };
        Ok(m2)
    }

    /// Processes M3. Only now is the helper key pinned.
    pub fn on_m3(&mut self, m3: &[u8], now_ms: u64) -> Result<Pinned, Error> {
        if now_ms > self.deadline_ms {
            return Err(self.fail(Error::Expired));
        }
        let state = core::mem::replace(&mut self.state, KadriState::Failed);
        let KadriState::SentConfirm { pairing_id, helper_public, output } = state else {
            return Err(Error::WrongState);
        };
        let b = parse(m3, 4, M3_LEN)?;
        if take::<ID_LEN>(b, 0) != pairing_id {
            return Err(Error::Malformed);
        }
        output.verify_peer_confirmation(&b[ID_LEN..]).map_err(|_| Error::ConfirmationFailed)?;
        self.state = KadriState::Done;
        Ok(Pinned { registration_id: pairing_id, peer_public: helper_public })
    }
}

// ---------------------------------------------------------------- Helper (B)

enum HelperState {
    Open,
    AwaitingConfirm { pairing_id: [u8; ID_LEN], kadri_public: [u8; KEY_LEN], st: PartyBState<Spake2P256> },
    Done,
    Burned,
}

/// Helper side. Opened only by an explicit user action in the native app,
/// which displays the freshly generated code. One attempt per window.
pub struct HelperPairingWindow {
    w: Option<Zeroizing<Scalar>>,
    helper_public: [u8; KEY_LEN],
    deadline_ms: u64,
    state: HelperState,
}

impl HelperPairingWindow {
    /// Generates a fresh uniformly random code. Returned once for display.
    pub fn generate_code() -> Result<String, Error> {
        // Rejection sampling over u32 for an unbiased value below 10^8.
        loop {
            let mut b = [0u8; 4];
            getrandom04::fill(&mut b).map_err(|_| Error::Crypto)?;
            let v = u32::from_le_bytes(b);
            let limit = u32::MAX - (u32::MAX % 100_000_000);
            if v < limit {
                return Ok(format!("{:08}", v % 100_000_000));
            }
        }
    }

    pub fn open(code: &str, helper_public: [u8; KEY_LEN], now_ms: u64, ttl_ms: u64) -> Result<Self, Error> {
        let w = Zeroizing::new(password_scalar(code)?);
        Ok(Self { w: Some(w), helper_public, deadline_ms: now_ms.saturating_add(ttl_ms), state: HelperState::Open })
    }

    pub fn is_burned(&self) -> bool {
        matches!(self.state, HelperState::Burned)
    }

    fn burn(&mut self, e: Error) -> Error {
        self.state = HelperState::Burned;
        self.w = None;
        e
    }

    /// Processes M0, returns M1. Consumes the single attempt immediately.
    pub fn on_m0(&mut self, m0: &[u8], now_ms: u64) -> Result<Vec<u8>, Error> {
        match self.state {
            HelperState::Open => {}
            HelperState::Burned => return Err(Error::AttemptsExhausted),
            _ => return Err(Error::WrongState), // no parallel or repeated pairing
        }
        if now_ms > self.deadline_ms {
            return Err(self.burn(Error::Expired));
        }
        let b = match parse(m0, 1, M0_LEN) { Ok(b) => b, Err(e) => return Err(self.burn(e)) };
        let kadri_public: [u8; KEY_LEN] = take(b, 0);
        let w = self.w.take().ok_or(Error::WrongState)?;
        let ida = identity_kadri(&kadri_public);
        let idb = identity_helper(&self.helper_public);
        let (pb, st) = match PartyB::<Spake2P256>::start(&w, &ida, &idb, PAKE_AAD, &mut rng()) {
            Ok(v) => v,
            Err(_) => return Err(self.burn(Error::Crypto)),
        };
        let pairing_id = match random_id() { Ok(v) => v, Err(e) => return Err(self.burn(e)) };
        let m1 = frame(2, &[&pairing_id, &self.helper_public, &pb]);
        self.state = HelperState::AwaitingConfirm { pairing_id, kadri_public, st };
        Ok(m1)
    }

    /// Processes M2, returns (pinned Kadri key, M3). Wrong code burns the window.
    pub fn on_m2(&mut self, m2: &[u8], now_ms: u64) -> Result<(Pinned, Vec<u8>), Error> {
        if now_ms > self.deadline_ms {
            return Err(self.burn(Error::Expired));
        }
        let state = core::mem::replace(&mut self.state, HelperState::Burned);
        let HelperState::AwaitingConfirm { pairing_id, kadri_public, st } = state else {
            return Err(match state { HelperState::Done | HelperState::Burned => Error::AttemptsExhausted, _ => Error::WrongState });
        };
        let b = parse(m2, 3, M2_LEN)?;
        if take::<ID_LEN>(b, 0) != pairing_id {
            return Err(Error::Malformed);
        }
        let pa = &b[ID_LEN..ID_LEN + SHARE_LEN];
        let ca = &b[ID_LEN + SHARE_LEN..];
        let output = st.finish(pa).map_err(|_| Error::Malformed)?;
        output.verify_peer_confirmation(ca).map_err(|_| Error::ConfirmationFailed)?;
        let m3 = frame(4, &[&pairing_id, &output.confirmation_mac]);
        self.state = HelperState::Done;
        Ok((Pinned { registration_id: pairing_id, peer_public: kadri_public }, m3))
    }
}
