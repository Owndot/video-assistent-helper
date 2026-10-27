//! JNI surface for the helper side (PoC only). Native state lives behind
//! numeric handles; Java never sees PAKE scalars or transport keys.
use crate::channel::{self, Channel};
use crate::pairing::HelperPairingWindow;
use crate::record::AppRecord;
use crate::{Error, Identity, ID_LEN, KEY_LEN};
use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jbyteArray, jint, jlong, jstring};
use jni::JNIEnv;
use std::collections::HashMap;
use std::sync::atomic::{AtomicI64, Ordering};
use std::sync::Mutex;

static NEXT: AtomicI64 = AtomicI64::new(1);
static WINDOWS: Mutex<Option<HashMap<i64, HelperPairingWindow>>> = Mutex::new(None);
static CHANNELS: Mutex<Option<HashMap<i64, (Channel, Vec<u8>)>>> = Mutex::new(None);

fn put<T>(m: &Mutex<Option<HashMap<i64, T>>>, v: T) -> i64 {
    let id = NEXT.fetch_add(1, Ordering::Relaxed);
    m.lock().unwrap().get_or_insert_with(HashMap::new).insert(id, v);
    id
}

fn with<T, R>(m: &Mutex<Option<HashMap<i64, T>>>, id: i64, f: impl FnOnce(&mut T) -> Result<R, Error>) -> Result<R, Error> {
    let mut g = m.lock().unwrap();
    let v = g.get_or_insert_with(HashMap::new).get_mut(&id).ok_or(Error::WrongState)?;
    f(v)
}

fn bytes(env: &JNIEnv, a: &JByteArray) -> Result<Vec<u8>, Error> {
    env.convert_byte_array(a).map_err(|_| Error::Malformed)
}

fn key(b: &[u8]) -> Result<[u8; KEY_LEN], Error> {
    b.try_into().map_err(|_| Error::Malformed)
}

fn throw(env: &mut JNIEnv, e: Error) {
    let _ = env.throw_new("java/lang/SecurityException", format!("{e:?}"));
}

fn ret(env: &mut JNIEnv, r: Result<Vec<u8>, Error>) -> jbyteArray {
    match r.and_then(|v| env.byte_array_from_slice(&v).map_err(|_| Error::Crypto)) {
        Ok(a) => a.into_raw(),
        Err(e) => {
            throw(env, e);
            std::ptr::null_mut()
        }
    }
}

fn ret_long(env: &mut JNIEnv, r: Result<i64, Error>) -> jlong {
    r.unwrap_or_else(|e| {
        throw(env, e);
        0
    })
}

#[no_mangle]
pub extern "system" fn Java_poc_Core_identityGenerate(mut env: JNIEnv, _: JClass) -> jbyteArray {
    let r = Identity::generate().map(|id| [&id.private[..], &id.public[..]].concat());
    ret(&mut env, r)
}

#[no_mangle]
pub extern "system" fn Java_poc_Core_generateCode(mut env: JNIEnv, _: JClass) -> jstring {
    match HelperPairingWindow::generate_code().and_then(|c| env.new_string(c).map_err(|_| Error::Crypto)) {
        Ok(s) => s.into_raw(),
        Err(e) => {
            throw(&mut env, e);
            std::ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_poc_Core_windowOpen(mut env: JNIEnv, _: JClass, code: JString, helper_public: JByteArray, now: jlong, ttl: jlong) -> jlong {
    let r = (|| {
        let code: String = env.get_string(&code).map_err(|_| Error::Malformed)?.into();
        let w = HelperPairingWindow::open(&code, key(&bytes(&env, &helper_public)?)?, now as u64, ttl as u64)?;
        Ok(put(&WINDOWS, w))
    })();
    ret_long(&mut env, r)
}

#[no_mangle]
pub extern "system" fn Java_poc_Core_windowOnM0(mut env: JNIEnv, _: JClass, h: jlong, m0: JByteArray, now: jlong) -> jbyteArray {
    let r = bytes(&env, &m0).and_then(|m0| with(&WINDOWS, h, |w| w.on_m0(&m0, now as u64)));
    ret(&mut env, r)
}

/// Returns registration_id(16) || kadri_public(32) || M3.
#[no_mangle]
pub extern "system" fn Java_poc_Core_windowOnM2(mut env: JNIEnv, _: JClass, h: jlong, m2: JByteArray, now: jlong) -> jbyteArray {
    let r = bytes(&env, &m2).and_then(|m2| {
        with(&WINDOWS, h, |w| w.on_m2(&m2, now as u64)).map(|(p, m3)| [&p.registration_id[..], &p.peer_public[..], &m3].concat())
    });
    ret(&mut env, r)
}

#[no_mangle]
pub extern "system" fn Java_poc_Core_windowFree(_: JNIEnv, _: JClass, h: jlong) {
    if let Some(m) = WINDOWS.lock().unwrap().as_mut() {
        m.remove(&h);
    }
}

/// Responds to HS1 for the registration Java looked up. `pinned` may be empty
/// (unknown/revoked registration), which fails closed.
#[no_mangle]
pub extern "system" fn Java_poc_Core_respond(mut env: JNIEnv, _: JClass, identity: JByteArray, hs1: JByteArray, pinned: JByteArray) -> jlong {
    let r = (|| {
        let id = bytes(&env, &identity)?;
        if id.len() != 2 * KEY_LEN {
            return Err(Error::Malformed);
        }
        let me = Identity::from_private(&id[..KEY_LEN])?;
        let hs1 = bytes(&env, &hs1)?;
        let pinned = bytes(&env, &pinned)?;
        let pinned = if pinned.is_empty() { None } else { Some(key(&pinned)?) };
        let (ch, hs2) = channel::respond(&me, &hs1, |_| pinned)?;
        Ok(put(&CHANNELS, (ch, hs2)))
    })();
    ret_long(&mut env, r)
}

#[no_mangle]
pub extern "system" fn Java_poc_Core_hs1RegistrationId(mut env: JNIEnv, _: JClass, hs1: JByteArray) -> jbyteArray {
    let r = bytes(&env, &hs1).and_then(|b| {
        if b.len() != channel::HS_LEN || &b[..4] != channel::MAGIC {
            return Err(Error::Malformed);
        }
        Ok(b[5..5 + ID_LEN].to_vec())
    });
    ret(&mut env, r)
}

#[no_mangle]
pub extern "system" fn Java_poc_Core_channelHs2(mut env: JNIEnv, _: JClass, h: jlong) -> jbyteArray {
    let r = with(&CHANNELS, h, |c| Ok(core::mem::take(&mut c.1)));
    ret(&mut env, r)
}

#[no_mangle]
pub extern "system" fn Java_poc_Core_channelSessionId(mut env: JNIEnv, _: JClass, h: jlong) -> jbyteArray {
    let r = with(&CHANNELS, h, |c| Ok(c.0.session_id().to_vec()));
    ret(&mut env, r)
}

/// Opens a record and returns the control JSON; anything else fails.
#[no_mangle]
pub extern "system" fn Java_poc_Core_channelOpenControl(mut env: JNIEnv, _: JClass, h: jlong, rec: JByteArray) -> jbyteArray {
    let r = bytes(&env, &rec).and_then(|rec| {
        with(&CHANNELS, h, |c| match AppRecord::decode(&c.0.open(&rec)?)? {
            AppRecord::Control(b) => Ok(b),
            _ => Err(Error::Malformed),
        })
    });
    ret(&mut env, r)
}

#[no_mangle]
pub extern "system" fn Java_poc_Core_channelSealControl(mut env: JNIEnv, _: JClass, h: jlong, json: JByteArray) -> jbyteArray {
    let r = bytes(&env, &json).and_then(|j| with(&CHANNELS, h, |c| c.0.seal(&AppRecord::Control(j).encode()?)));
    ret(&mut env, r)
}

#[no_mangle]
pub extern "system" fn Java_poc_Core_channelSealMedia(mut env: JNIEnv, _: JClass, h: jlong, transfer: jint, offset: jlong, data: JByteArray) -> jbyteArray {
    let r = bytes(&env, &data).and_then(|d| {
        with(&CHANNELS, h, |c| c.0.seal(&AppRecord::Media { transfer: transfer as u32, offset: offset as u64, data: d }.encode()?))
    });
    ret(&mut env, r)
}

#[no_mangle]
pub extern "system" fn Java_poc_Core_channelSealEnd(mut env: JNIEnv, _: JClass, h: jlong, transfer: jint, total: jlong) -> jbyteArray {
    let r = with(&CHANNELS, h, |c| c.0.seal(&AppRecord::End { transfer: transfer as u32, total: total as u64 }.encode()?));
    ret(&mut env, r)
}

#[no_mangle]
pub extern "system" fn Java_poc_Core_channelFree(_: JNIEnv, _: JClass, h: jlong) {
    if let Some(m) = CHANNELS.lock().unwrap().as_mut() {
        m.remove(&h);
    }
}
