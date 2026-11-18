#![allow(dead_code)]
use kadri_pairing_poc::pairing::{HelperPairingWindow, KadriPairing, Pinned};
use kadri_pairing_poc::{Error, Identity};

pub const CODE: &str = "40719283";
pub const WRONG: &str = "40719284";
pub const TTL: u64 = 120_000;

pub fn ids() -> (Identity, Identity) {
    (Identity::generate().unwrap(), Identity::generate().unwrap())
}

/// Full exchange; returns (kadri pin, helper pin) or the failing step.
pub fn pair(kadri: &Identity, helper: &Identity, kcode: &str, hcode: &str) -> Result<(Pinned, Pinned), (Error, &'static str)> {
    let mut win = HelperPairingWindow::open(hcode, helper.public, 0, TTL).unwrap();
    let (mut kp, m0) = KadriPairing::start(kcode, kadri.public, 0, TTL).unwrap();
    let m1 = win.on_m0(&m0, 1).map_err(|e| (e, "helper m0"))?;
    let m2 = kp.on_m1(&m1, 2).map_err(|e| (e, "kadri m1"))?;
    let (hp, m3) = win.on_m2(&m2, 3).map_err(|e| (e, "helper m2"))?;
    let kpin = kp.on_m3(&m3, 4).map_err(|e| (e, "kadri m3"))?;
    Ok((kpin, hp))
}

pub fn hex32(s: &str) -> [u8; 32] {
    hex::decode(s).unwrap().try_into().unwrap()
}

/// Low-order and non-canonical X25519 encodings (libsodium blocklist values).
pub const BAD_KEYS: &[&str] = &[
    "0000000000000000000000000000000000000000000000000000000000000000",
    "0100000000000000000000000000000000000000000000000000000000000000",
    "e0eb7a7c3b41b8ae1656e3faf19fc46ada098deb9c32b1fd866205165f49b800",
    "5f9c95bca3508c24b1d0b1559c83ef5b04445cc4581c8e86d8224eddd09f1157",
    "ecffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
    "edffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
    "eeffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
    "cdeb7a7c3b41b8ae1656e3faf19fc46ada098deb9c32b1fd866205165f49b880",
    "4c9c95bca3508c24b1d0b1559c83ef5b04445cc4581c8e86d8224eddd09f11d7",
    "d9ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
    "daffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
    "dbffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
];
