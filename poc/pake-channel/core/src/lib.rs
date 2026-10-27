//! ISOLATED FEASIBILITY PROOF — NOT PRODUCTION CODE.
//!
//! Composition under evaluation (no primitive, KDF, AEAD or handshake is
//! implemented here; this crate only sequences library calls and frames bytes):
//!
//! 1. Pairing: SPAKE2 (RFC 9382, P256-SHA256-HKDF-SHA256-HMAC-SHA256) via
//!    `pakery-spake2 =0.6.2`. The two long-term X25519 public keys are the
//!    RFC 9382 identities A and B, so the mutual key confirmation of the PAKE
//!    authenticates exactly these keys. The PAKE session key Ke is unused.
//! 2. Channel: `Noise_KK_25519_ChaChaPoly_SHA256` via `snow =0.10.0` with the
//!    pinned static keys from step 1. Every connection runs a fresh handshake.
//!
//! All keys used with this crate in tests are throwaway material.

pub mod channel;
pub mod pairing;
pub mod record;

#[cfg(feature = "jni")]
pub mod jni_api;
#[cfg(feature = "wasm")]
pub mod wasm_api;

use zeroize::Zeroizing;

/// Fixed protocol identifiers. There is no negotiation and no fallback.
pub const NOISE_PARAMS: &str = "Noise_KK_25519_ChaChaPoly_SHA256";
pub const NOISE_PROLOGUE: &[u8] = b"kadri-helper-channel-poc/1";
pub const PAKE_AAD: &[u8] = b"kadri-helper-pairing-poc/1";
pub const ID_PREFIX_KADRI: &[u8] = b"kadri-ext-x25519/1:";
pub const ID_PREFIX_HELPER: &[u8] = b"kadri-helper-x25519/1:";

pub const KEY_LEN: usize = 32;
pub const ID_LEN: usize = 16;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Error {
    /// Input frame has the wrong magic, type, length or identifier.
    Malformed,
    /// The state machine is not in a state that accepts this message.
    WrongState,
    /// Pairing window or flow deadline elapsed.
    Expired,
    /// The code was already consumed by an earlier attempt.
    AttemptsExhausted,
    /// PAKE key confirmation failed (wrong code, MITM, altered identity...).
    ConfirmationFailed,
    /// Noise handshake or record authentication failed.
    AuthFailed,
    /// Size bound exceeded.
    TooLarge,
    /// Library error not covered above.
    Crypto,
}

/// A long-term X25519 identity. Private bytes are zeroized on drop.
pub struct Identity {
    pub private: Zeroizing<[u8; KEY_LEN]>,
    pub public: [u8; KEY_LEN],
}

impl Identity {
    /// Generates a key pair with snow's resolver (getrandom-backed RNG).
    pub fn generate() -> Result<Self, Error> {
        let builder = snow::Builder::new(NOISE_PARAMS.parse().map_err(|_| Error::Crypto)?);
        let kp = builder.generate_keypair().map_err(|_| Error::Crypto)?;
        Self::from_private(&kp.private)
    }

    pub fn from_private(private: &[u8]) -> Result<Self, Error> {
        if private.len() != KEY_LEN {
            return Err(Error::Malformed);
        }
        let mut sk = Zeroizing::new([0u8; KEY_LEN]);
        sk.copy_from_slice(private);
        let public = x25519_public(&sk)?;
        Ok(Self { private: sk, public })
    }
}

/// Public key of a stored private key, computed by snow's own DH resolver.
fn x25519_public(sk: &[u8; KEY_LEN]) -> Result<[u8; KEY_LEN], Error> {
    use snow::params::DHChoice;
    use snow::resolvers::{CryptoResolver, DefaultResolver};
    let mut dh = DefaultResolver.resolve_dh(&DHChoice::Curve25519).ok_or(Error::Crypto)?;
    dh.set(sk);
    let mut out = [0u8; KEY_LEN];
    out.copy_from_slice(dh.pubkey());
    Ok(out)
}

pub(crate) fn random_id() -> Result<[u8; ID_LEN], Error> {
    let mut id = [0u8; ID_LEN];
    getrandom04::fill(&mut id).map_err(|_| Error::Crypto)?;
    Ok(id)
}
