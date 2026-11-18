//! Kadri-side (initiator) JNI surface for on-device instrumentation tests of
//! the Android test app only (feature `jni-test-initiator`). It lets an
//! androidTest drive pairing, handshake and records over the real loopback
//! socket without a browser. It is reachable only through code running in
//! the test app's own process; it exposes no IPC, socket or intent entry.
use crate::channel::{Channel, Initiator};
use crate::pairing::KadriPairing;
use crate::record::AppRecord;
use crate::{Error, Identity, ID_LEN, KEY_LEN};
use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jbyteArray, jlong};
use jni::JNIEnv;
use std::collections::HashMap;
use std::sync::atomic::{AtomicI64, Ordering};
use std::sync::Mutex;

enum Obj {
    Pairing(KadriPairing, Vec<u8>),
    Handshake(Initiator, Vec<u8>),
    Channel(Channel),
}

static NEXT: AtomicI64 = AtomicI64::new(1);
static OBJS: Mutex<Option<HashMap<i64, Obj>>> = Mutex::new(None);

fn put(o: Obj) -> i64 {
    let id = NEXT.fetch_add(1, Ordering::Relaxed);
    OBJS.lock().unwrap().get_or_insert_with(HashMap::new).insert(id, o);
    id
}

fn with<R>(id: i64, f: impl FnOnce(&mut Obj) -> Result<R, Error>) -> Result<R, Error> {
    let mut g = OBJS.lock().map_err(|_| Error::Crypto)?;
    f(g.get_or_insert_with(HashMap::new).get_mut(&id).ok_or(Error::WrongState)?)
}

fn bytes(env: &JNIEnv, a: &JByteArray) -> Result<Vec<u8>, Error> {
    env.convert_byte_array(a).map_err(|_| Error::Malformed)
}

fn ret(env: &mut JNIEnv, r: Result<Vec<u8>, Error>) -> jbyteArray {
    match r.and_then(|v| env.byte_array_from_slice(&v).map_err(|_| Error::Crypto)) {
        Ok(a) => a.into_raw(),
        Err(e) => {
            let _ = env.throw_new("java/lang/SecurityException", format!("{e:?}"));
            std::ptr::null_mut()
        }
    }
}

fn ret_long(env: &mut JNIEnv, r: Result<i64, Error>) -> jlong {
    r.unwrap_or_else(|e| {
        let _ = env.throw_new("java/lang/SecurityException", format!("{e:?}"));
        0
    })
}

fn identity(b: &[u8]) -> Result<Identity, Error> {
    if b.len() != 2 * KEY_LEN {
        return Err(Error::Malformed);
    }
    Identity::from_private(&b[..KEY_LEN])
}

#[no_mangle]
pub extern "system" fn Java_poc_TestInitiator_pairStart(mut env: JNIEnv, _: JClass, id: JByteArray, code: JString, now: jlong, ttl: jlong) -> jlong {
    let r = (|| {
        let me = identity(&bytes(&env, &id)?)?;
        let code: String = env.get_string(&code).map_err(|_| Error::Malformed)?.into();
        let (p, m0) = KadriPairing::start(&code, me.public, now as u64, ttl as u64)?;
        Ok(put(Obj::Pairing(p, m0)))
    })();
    ret_long(&mut env, r)
}

#[no_mangle]
pub extern "system" fn Java_poc_TestInitiator_m0(mut env: JNIEnv, _: JClass, h: jlong) -> jbyteArray {
    let r = with(h, |o| match o {
        Obj::Pairing(_, m0) => Ok(m0.clone()),
        _ => Err(Error::WrongState),
    });
    ret(&mut env, r)
}

#[no_mangle]
pub extern "system" fn Java_poc_TestInitiator_onM1(mut env: JNIEnv, _: JClass, h: jlong, m1: JByteArray, now: jlong) -> jbyteArray {
    let r = bytes(&env, &m1).and_then(|m1| {
        with(h, |o| match o {
            Obj::Pairing(p, _) => p.on_m1(&m1, now as u64),
            _ => Err(Error::WrongState),
        })
    });
    ret(&mut env, r)
}

/// Returns registration_id(16) || helper_public(32).
#[no_mangle]
pub extern "system" fn Java_poc_TestInitiator_onM3(mut env: JNIEnv, _: JClass, h: jlong, m3: JByteArray, now: jlong) -> jbyteArray {
    let r = bytes(&env, &m3).and_then(|m3| {
        with(h, |o| match o {
            Obj::Pairing(p, _) => p.on_m3(&m3, now as u64).map(|pin| [&pin.registration_id[..], &pin.peer_public[..]].concat()),
            _ => Err(Error::WrongState),
        })
    });
    ret(&mut env, r)
}

#[no_mangle]
pub extern "system" fn Java_poc_TestInitiator_handshakeStart(mut env: JNIEnv, _: JClass, id: JByteArray, helper: JByteArray, reg: JByteArray) -> jlong {
    let r = (|| {
        let me = identity(&bytes(&env, &id)?)?;
        let helper: [u8; KEY_LEN] = bytes(&env, &helper)?.try_into().map_err(|_| Error::Malformed)?;
        let reg: [u8; ID_LEN] = bytes(&env, &reg)?.try_into().map_err(|_| Error::Malformed)?;
        let (i, hs1) = Initiator::start(&me, &helper, &reg)?;
        Ok(put(Obj::Handshake(i, hs1)))
    })();
    ret_long(&mut env, r)
}

#[no_mangle]
pub extern "system" fn Java_poc_TestInitiator_hs1(mut env: JNIEnv, _: JClass, h: jlong) -> jbyteArray {
    let r = with(h, |o| match o {
        Obj::Handshake(_, hs1) => Ok(hs1.clone()),
        _ => Err(Error::WrongState),
    });
    ret(&mut env, r)
}

/// Consumes the handshake object; on success the same handle is the channel.
#[no_mangle]
pub extern "system" fn Java_poc_TestInitiator_finish(mut env: JNIEnv, _: JClass, h: jlong, hs2: JByteArray) -> jbyteArray {
    let r = bytes(&env, &hs2).and_then(|hs2| {
        with(h, |o| {
            let ch = match o {
                Obj::Handshake(i, _) => i.finish(&hs2)?,
                _ => return Err(Error::WrongState),
            };
            let sid = ch.session_id().to_vec();
            *o = Obj::Channel(ch);
            Ok(sid)
        })
    });
    ret(&mut env, r)
}

#[no_mangle]
pub extern "system" fn Java_poc_TestInitiator_sealControl(mut env: JNIEnv, _: JClass, h: jlong, json: JByteArray) -> jbyteArray {
    let r = bytes(&env, &json).and_then(|j| {
        with(h, |o| match o {
            Obj::Channel(c) => c.seal_app(&AppRecord::Control(j)),
            _ => Err(Error::WrongState),
        })
    });
    ret(&mut env, r)
}

/// Returns kind(1) || body as in the WASM adapter.
#[no_mangle]
pub extern "system" fn Java_poc_TestInitiator_open(mut env: JNIEnv, _: JClass, h: jlong, rec: JByteArray) -> jbyteArray {
    let r = bytes(&env, &rec).and_then(|rec| {
        with(h, |o| match o {
            Obj::Channel(c) => Ok(match c.open_app(&rec)? {
                AppRecord::Control(b) => [&[1u8][..], &b].concat(),
                AppRecord::Media { transfer, offset, data } => [&[2u8][..], &transfer.to_be_bytes(), &offset.to_be_bytes(), &data].concat(),
                AppRecord::End { transfer, total } => [&[3u8][..], &transfer.to_be_bytes(), &total.to_be_bytes()].concat(),
            }),
            _ => Err(Error::WrongState),
        })
    });
    ret(&mut env, r)
}

#[no_mangle]
pub extern "system" fn Java_poc_TestInitiator_free(_: JNIEnv, _: JClass, h: jlong) {
    if let Some(m) = OBJS.lock().unwrap().as_mut() {
        m.remove(&h);
    }
}
