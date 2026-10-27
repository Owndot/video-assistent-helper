//! wasm-bindgen surface for the Kadri extension background (PoC only).
use crate::channel::{Channel, Initiator};
use crate::pairing::KadriPairing;
use crate::record::AppRecord;
use crate::{Error, Identity, ID_LEN, KEY_LEN};
use wasm_bindgen::prelude::*;

fn js(e: Error) -> JsError {
    JsError::new(&format!("{e:?}"))
}

fn key(b: &[u8]) -> Result<[u8; KEY_LEN], JsError> {
    b.try_into().map_err(|_| js(Error::Malformed))
}

/// Returns private(32) || public(32). The caller stores it only in the
/// extension's own origin storage, never in content-script reachable storage.
#[wasm_bindgen]
pub fn identity_generate() -> Result<Vec<u8>, JsError> {
    let id = Identity::generate().map_err(js)?;
    Ok([&id.private[..], &id.public[..]].concat())
}

#[wasm_bindgen]
pub struct KadriPairingFlow {
    inner: KadriPairing,
    m0: Vec<u8>,
}

#[wasm_bindgen]
impl KadriPairingFlow {
    #[wasm_bindgen(constructor)]
    pub fn new(code: &str, kadri_public: &[u8], now_ms: f64, ttl_ms: f64) -> Result<KadriPairingFlow, JsError> {
        let (inner, m0) = KadriPairing::start(code, key(kadri_public)?, now_ms as u64, ttl_ms as u64).map_err(js)?;
        Ok(Self { inner, m0 })
    }
    pub fn m0(&self) -> Vec<u8> {
        self.m0.clone()
    }
    pub fn on_m1(&mut self, m1: &[u8], now_ms: f64) -> Result<Vec<u8>, JsError> {
        self.inner.on_m1(m1, now_ms as u64).map_err(js)
    }
    /// Returns registration_id(16) || helper_public(32) to pin.
    pub fn on_m3(&mut self, m3: &[u8], now_ms: f64) -> Result<Vec<u8>, JsError> {
        let p = self.inner.on_m3(m3, now_ms as u64).map_err(js)?;
        Ok([&p.registration_id[..], &p.peer_public[..]].concat())
    }
}

#[wasm_bindgen]
pub struct KadriHandshake {
    inner: Option<Initiator>,
    hs1: Vec<u8>,
}

#[wasm_bindgen]
impl KadriHandshake {
    #[wasm_bindgen(constructor)]
    pub fn new(identity: &[u8], helper_public: &[u8], registration_id: &[u8]) -> Result<KadriHandshake, JsError> {
        if identity.len() != 2 * KEY_LEN || registration_id.len() != ID_LEN {
            return Err(js(Error::Malformed));
        }
        let me = Identity::from_private(&identity[..KEY_LEN]).map_err(js)?;
        let reg: [u8; ID_LEN] = registration_id.try_into().unwrap();
        let (inner, hs1) = Initiator::start(&me, &key(helper_public)?, &reg).map_err(js)?;
        Ok(Self { inner: Some(inner), hs1 })
    }
    pub fn hs1(&self) -> Vec<u8> {
        self.hs1.clone()
    }
    pub fn finish(&mut self, hs2: &[u8]) -> Result<KadriChannel, JsError> {
        let init = self.inner.take().ok_or(js(Error::WrongState))?;
        Ok(KadriChannel { inner: init.finish(hs2).map_err(js)? })
    }
}

#[wasm_bindgen]
pub struct KadriChannel {
    inner: Channel,
}

#[wasm_bindgen]
impl KadriChannel {
    pub fn seal_control(&mut self, json: &[u8]) -> Result<Vec<u8>, JsError> {
        let rec = AppRecord::Control(json.to_vec()).encode().map_err(js)?;
        self.inner.seal(&rec).map_err(js)
    }
    /// Returns [kind] || body: kind 1 control JSON, 2 media (u32 transfer,
    /// u64 offset, data), 3 end (u32 transfer, u64 total).
    pub fn open(&mut self, record: &[u8]) -> Result<Vec<u8>, JsError> {
        let pt = self.inner.open(record).map_err(js)?;
        Ok(match AppRecord::decode(&pt).map_err(js)? {
            AppRecord::Control(b) => [&[1u8][..], &b].concat(),
            AppRecord::Media { transfer, offset, data } => [&[2u8][..], &transfer.to_be_bytes(), &offset.to_be_bytes(), &data].concat(),
            AppRecord::End { transfer, total } => [&[3u8][..], &transfer.to_be_bytes(), &total.to_be_bytes()].concat(),
        })
    }
}
