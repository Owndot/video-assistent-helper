//! JNI surface for the helper side (PoC only). One `HelperHost` per process
//! plus at most one pairing window. Java never sees PAKE scalars, CipherStates
//! or session keys; it only moves bytes, persists the exported registrations
//! and dispatches decrypted control records.
use crate::host::{HelperHost, Limits};
use crate::pairing::HelperPairingWindow;
use crate::record::AppRecord;
use crate::{Error, Identity, ID_LEN, KEY_LEN};
use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jboolean, jbyteArray, jint, jlong, jstring};
use jni::JNIEnv;
use std::sync::Mutex;

struct State {
    host: HelperHost,
    window: Option<HelperPairingWindow>,
}

static STATE: Mutex<Option<State>> = Mutex::new(None);

fn with<R>(f: impl FnOnce(&mut State) -> Result<R, Error>) -> Result<R, Error> {
    let mut g = STATE.lock().map_err(|_| Error::Crypto)?;
    f(g.as_mut().ok_or(Error::WrongState)?)
}

fn bytes(env: &JNIEnv, a: &JByteArray) -> Result<Vec<u8>, Error> {
    env.convert_byte_array(a).map_err(|_| Error::Malformed)
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

fn ret_str(env: &mut JNIEnv, r: Result<String, Error>) -> jstring {
    match r.and_then(|s| env.new_string(s).map_err(|_| Error::Crypto)) {
        Ok(s) => s.into_raw(),
        Err(e) => {
            throw(env, e);
            std::ptr::null_mut()
        }
    }
}

fn sid(b: &[u8]) -> Result<[u8; ID_LEN], Error> {
    b.try_into().map_err(|_| Error::Malformed)
}

#[no_mangle]
pub extern "system" fn Java_poc_Core_identityGenerate(mut env: JNIEnv, _: JClass) -> jbyteArray {
    let r = Identity::generate().map(|id| [&id.private[..], &id.public[..]].concat());
    ret(&mut env, r)
}

/// Loads the helper identity (private||public, 64 B) and persisted
/// registrations. `now` is the caller's monotonic clock; `same_boot` is false
/// when the persisted data predates the current boot (see HelperHost).
#[no_mangle]
pub extern "system" fn Java_poc_Core_hostInit(mut env: JNIEnv, _: JClass, identity: JByteArray, registrations: JByteArray, now: jlong, same_boot: jboolean) -> jstring {
    let r = (|| {
        let id = bytes(&env, &identity)?;
        if id.len() != 2 * KEY_LEN {
            return Err(Error::Malformed);
        }
        let me = Identity::from_private(&id[..KEY_LEN])?;
        if me.public[..] != id[KEY_LEN..] {
            return Err(Error::Malformed);
        }
        let mut host = HelperHost::new(me, Limits::default());
        host.import_registrations(&bytes(&env, &registrations)?, now as u64, same_boot != 0)?;
        let public: String = host.public().iter().map(|b| format!("{b:02x}")).collect();
        *STATE.lock().map_err(|_| Error::Crypto)? = Some(State { host, window: None });
        Ok(public)
    })();
    ret_str(&mut env, r)
}

/// Opens the single pairing window and returns the code for display.
#[no_mangle]
pub extern "system" fn Java_poc_Core_windowOpen(mut env: JNIEnv, _: JClass, code: JString, now: jlong, ttl: jlong) -> jstring {
    let given: Option<String> = if code.is_null() { None } else { env.get_string(&code).ok().map(Into::into) };
    let r = with(|s| {
        let code = match given {
            Some(c) => c,
            None => HelperPairingWindow::generate_code()?,
        };
        s.window = Some(HelperPairingWindow::open(&code, s.host.public(), now as u64, ttl as u64)?);
        Ok(code)
    });
    ret_str(&mut env, r)
}

#[no_mangle]
pub extern "system" fn Java_poc_Core_windowOnM0(mut env: JNIEnv, _: JClass, m0: JByteArray, now: jlong) -> jbyteArray {
    let r = bytes(&env, &m0).and_then(|m0| with(|s| s.window.as_mut().ok_or(Error::WrongState)?.on_m0(&m0, now as u64)));
    ret(&mut env, r)
}

/// Returns registration_id(16) || M3. The window is closed afterwards in
/// every case; the registration is added as pending before M3 is released.
#[no_mangle]
pub extern "system" fn Java_poc_Core_windowOnM2(mut env: JNIEnv, _: JClass, m2: JByteArray, now: jlong) -> jbyteArray {
    let r = bytes(&env, &m2).and_then(|m2| {
        with(|s| {
            let mut w = s.window.take().ok_or(Error::WrongState)?;
            let (pinned, m3) = w.on_m2(&m2, now as u64)?;
            s.host.add_pending(&pinned, now as u64)?;
            Ok([&pinned.registration_id[..], &m3].concat())
        })
    });
    ret(&mut env, r)
}

#[no_mangle]
pub extern "system" fn Java_poc_Core_hostHandshake(mut env: JNIEnv, _: JClass, hs1: JByteArray, now: jlong) -> jbyteArray {
    let r = bytes(&env, &hs1).and_then(|hs1| with(|s| s.host.handshake(&hs1, now as u64)));
    ret(&mut env, r)
}

/// Returns sid(16) || kind(1) || body. kind 1 = control JSON. Other
/// application record kinds from Kadri are rejected (terminal).
#[no_mangle]
pub extern "system" fn Java_poc_Core_hostOpen(mut env: JNIEnv, _: JClass, rec: JByteArray, now: jlong) -> jbyteArray {
    let r = bytes(&env, &rec).and_then(|rec| {
        with(|s| match s.host.open(&rec, now as u64)? {
            (sid, AppRecord::Control(b)) => Ok([&sid[..], &[1u8], &b].concat()),
            (sid, _) => {
                s.host.close(&sid);
                Err(Error::Malformed)
            }
        })
    });
    ret(&mut env, r)
}

#[no_mangle]
pub extern "system" fn Java_poc_Core_hostSealControl(mut env: JNIEnv, _: JClass, session: JByteArray, json: JByteArray, now: jlong) -> jbyteArray {
    let r = bytes(&env, &session).and_then(|id| {
        let j = bytes(&env, &json)?;
        with(|s| s.host.seal(&sid(&id)?, &AppRecord::Control(j), now as u64))
    });
    ret(&mut env, r)
}

#[no_mangle]
pub extern "system" fn Java_poc_Core_hostSealMedia(mut env: JNIEnv, _: JClass, session: JByteArray, transfer: jint, offset: jlong, data: JByteArray, now: jlong) -> jbyteArray {
    let r = bytes(&env, &session).and_then(|id| {
        let d = bytes(&env, &data)?;
        with(|s| s.host.seal(&sid(&id)?, &AppRecord::Media { transfer: transfer as u32, offset: offset as u64, data: d }, now as u64))
    });
    ret(&mut env, r)
}

#[no_mangle]
pub extern "system" fn Java_poc_Core_hostSealEnd(mut env: JNIEnv, _: JClass, session: JByteArray, transfer: jint, total: jlong, now: jlong) -> jbyteArray {
    let r = bytes(&env, &session).and_then(|id| with(|s| s.host.seal(&sid(&id)?, &AppRecord::End { transfer: transfer as u32, total: total as u64 }, now as u64)));
    ret(&mut env, r)
}

#[no_mangle]
pub extern "system" fn Java_poc_Core_hostExportRegistrations(mut env: JNIEnv, _: JClass) -> jbyteArray {
    let r = with(|s| Ok(s.host.export_registrations()));
    ret(&mut env, r)
}

/// Revokes one registration (16-byte id) and ends its sessions, using the
/// host's existing `revoke`. Unknown ids are a no-op; malformed ids throw.
#[no_mangle]
pub extern "system" fn Java_poc_Core_hostRevoke(mut env: JNIEnv, _: JClass, id: JByteArray) {
    if let Err(e) = bytes(&env, &id).and_then(|b| sid(&b)).and_then(|id| {
        with(|s| {
            s.host.revoke(&id);
            Ok(())
        })
    }) {
        throw(&mut env, e);
    }
}

#[no_mangle]
pub extern "system" fn Java_poc_Core_hostRevokeAll(mut env: JNIEnv, _: JClass) {
    if let Err(e) = with(|s| {
        s.host.revoke_all();
        s.window = None;
        Ok(())
    }) {
        throw(&mut env, e);
    }
}

#[no_mangle]
pub extern "system" fn Java_poc_Core_hostStats(mut env: JNIEnv, _: JClass, now: jlong) -> jstring {
    let r = with(|s| {
        s.host.sweep(now as u64);
        let st = s.host.stats();
        Ok(format!("registrations={} half_open={} established={}", st.registrations, st.half_open, st.established))
    });
    ret_str(&mut env, r)
}
