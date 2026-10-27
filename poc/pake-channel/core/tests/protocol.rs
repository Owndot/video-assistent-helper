//! Positive and negative protocol tests with throwaway identities and
//! artificial codes only.
use kadri_pairing_poc::channel::{self, Channel, Initiator, HS_LEN, MAX_PLAINTEXT};
use kadri_pairing_poc::pairing::{HelperPairingWindow, KadriPairing, Pinned};
use kadri_pairing_poc::record::{AppRecord, MAX_MEDIA};
use kadri_pairing_poc::{Error, Identity};

const CODE: &str = "40719283";
const WRONG: &str = "40719284";
const TTL: u64 = 120_000;

struct Run {
    kadri: Pinned,
    helper: Pinned,
}

fn pair(kadri: &Identity, helper: &Identity, kcode: &str, hcode: &str) -> Result<Run, (Error, &'static str)> {
    let mut win = HelperPairingWindow::open(hcode, helper.public, 0, TTL).unwrap();
    let (mut kp, m0) = KadriPairing::start(kcode, kadri.public, 0, TTL).unwrap();
    let m1 = win.on_m0(&m0, 1).map_err(|e| (e, "helper m0"))?;
    let m2 = kp.on_m1(&m1, 2).map_err(|e| (e, "kadri m1"))?;
    let (hp, m3) = win.on_m2(&m2, 3).map_err(|e| (e, "helper m2"))?;
    let kpin = kp.on_m3(&m3, 4).map_err(|e| (e, "kadri m3"))?;
    Ok(Run { kadri: kpin, helper: hp })
}

#[test]
fn pairing_pins_both_long_term_keys() {
    let (k, h) = (Identity::generate().unwrap(), Identity::generate().unwrap());
    let r = pair(&k, &h, CODE, CODE).unwrap();
    assert_eq!(r.kadri.peer_public, h.public);
    assert_eq!(r.helper.peer_public, k.public);
    assert_eq!(r.kadri.registration_id, r.helper.registration_id);
}

#[test]
fn wrong_code_fails_and_burns_the_window() {
    let (k, h) = (Identity::generate().unwrap(), Identity::generate().unwrap());
    let mut win = HelperPairingWindow::open(CODE, h.public, 0, TTL).unwrap();
    let (mut kp, m0) = KadriPairing::start(WRONG, k.public, 0, TTL).unwrap();
    let m1 = win.on_m0(&m0, 1).unwrap();
    let m2 = kp.on_m1(&m1, 2).unwrap();
    assert_eq!(win.on_m2(&m2, 3).err(), Some(Error::ConfirmationFailed));
    assert!(win.is_burned());
    // The correct code cannot be tried in the same window afterwards.
    let (_, m0b) = KadriPairing::start(CODE, k.public, 4, TTL).unwrap();
    assert_eq!(win.on_m0(&m0b, 5).err(), Some(Error::AttemptsExhausted));
}

/// A foreign listener on the helper port runs the helper role with a guessed
/// code and forges every message it can. Kadri must not pin anything.
#[test]
fn rogue_listener_with_guessed_code_is_rejected_by_kadri() {
    let (k, rogue) = (Identity::generate().unwrap(), Identity::generate().unwrap());
    let mut fake = HelperPairingWindow::open(WRONG, rogue.public, 0, TTL).unwrap();
    let (mut kp, m0) = KadriPairing::start(CODE, k.public, 0, TTL).unwrap();
    let m1 = fake.on_m0(&m0, 1).unwrap();
    let m2 = kp.on_m1(&m1, 2).unwrap();
    // The rogue cannot verify cA and cannot produce cB; forge one.
    let mut m3 = b"KHP1\x04".to_vec();
    m3.extend_from_slice(&m2[5..21]);
    m3.extend_from_slice(&[0x5a; 32]);
    assert_eq!(kp.on_m3(&m3, 3).err(), Some(Error::ConfirmationFailed));
    // Kadri state is terminal: no second confirmation attempt.
    assert_eq!(kp.on_m3(&m3, 3).err(), Some(Error::WrongState));
}

/// An active MITM relays all PAKE values but substitutes its own long-term
/// key for either party's identity. Confirmation binds identities, so it fails.
#[test]
fn identity_substitution_by_mitm_is_detected() {
    let (k, h, evil) = (Identity::generate().unwrap(), Identity::generate().unwrap(), Identity::generate().unwrap());
    // Substitute Kadri's key in M0.
    let mut win = HelperPairingWindow::open(CODE, h.public, 0, TTL).unwrap();
    let (mut kp, mut m0) = KadriPairing::start(CODE, k.public, 0, TTL).unwrap();
    m0[5..37].copy_from_slice(&evil.public);
    let m1 = win.on_m0(&m0, 1).unwrap();
    let m2 = kp.on_m1(&m1, 2).unwrap();
    assert_eq!(win.on_m2(&m2, 3).err(), Some(Error::ConfirmationFailed));

    // Substitute the helper key in M1.
    let mut win = HelperPairingWindow::open(CODE, h.public, 0, TTL).unwrap();
    let (mut kp, m0) = KadriPairing::start(CODE, k.public, 0, TTL).unwrap();
    let mut m1 = win.on_m0(&m0, 1).unwrap();
    m1[21..53].copy_from_slice(&evil.public);
    let m2 = kp.on_m1(&m1, 2).unwrap();
    assert_eq!(win.on_m2(&m2, 3).err(), Some(Error::ConfirmationFailed));
}

#[test]
fn modified_pake_shares_are_detected() {
    let (k, h) = (Identity::generate().unwrap(), Identity::generate().unwrap());
    // Swap pB for a different valid point (another run's share).
    let mut other = HelperPairingWindow::open(CODE, h.public, 0, TTL).unwrap();
    let (_, m0x) = KadriPairing::start(CODE, k.public, 0, TTL).unwrap();
    let foreign_m1 = other.on_m0(&m0x, 1).unwrap();
    let mut win = HelperPairingWindow::open(CODE, h.public, 0, TTL).unwrap();
    let (mut kp, m0) = KadriPairing::start(CODE, k.public, 0, TTL).unwrap();
    let mut m1 = win.on_m0(&m0, 1).unwrap();
    m1[53..].copy_from_slice(&foreign_m1[53..]);
    let m2 = kp.on_m1(&m1, 2).unwrap();
    assert_eq!(win.on_m2(&m2, 3).err(), Some(Error::ConfirmationFailed));
    // Invalid point encoding is rejected outright.
    let (mut kp, m0) = KadriPairing::start(CODE, k.public, 0, TTL).unwrap();
    let mut win = HelperPairingWindow::open(CODE, h.public, 0, TTL).unwrap();
    let mut m1 = win.on_m0(&m0, 1).unwrap();
    m1[54] ^= 0x01;
    assert!(kp.on_m1(&m1, 2).is_err());
}

#[test]
fn reflected_and_replayed_pairing_messages_are_rejected() {
    let (k, h) = (Identity::generate().unwrap(), Identity::generate().unwrap());
    let mut win = HelperPairingWindow::open(CODE, h.public, 0, TTL).unwrap();
    let (mut kp, m0) = KadriPairing::start(CODE, k.public, 0, TTL).unwrap();
    let m1 = win.on_m0(&m0, 1).unwrap();
    let m2 = kp.on_m1(&m1, 2).unwrap();
    // Reflection: Kadri's own M2 echoed back as M3, helper's M1 echoed as M2.
    let mut echoed = m2[..5 + 16 + 32].to_vec();
    echoed[4] = 4;
    let mut kp2 = kp;
    assert!(kp2.on_m3(&echoed, 3).is_err());
    let mut win2 = HelperPairingWindow::open(CODE, h.public, 0, TTL).unwrap();
    let _ = win2.on_m0(&m0, 1).unwrap();
    assert!(win2.on_m2(&m1, 2).is_err());

    // Replay of a complete old exchange against a new window with the SAME code.
    let mut old_win = HelperPairingWindow::open(CODE, h.public, 0, TTL).unwrap();
    let (mut old_kp, old_m0) = KadriPairing::start(CODE, k.public, 0, TTL).unwrap();
    let old_m1 = old_win.on_m0(&old_m0, 1).unwrap();
    let old_m2 = old_kp.on_m1(&old_m1, 2).unwrap();
    let (_, old_m3) = old_win.on_m2(&old_m2, 3).unwrap();
    let mut new_win = HelperPairingWindow::open(CODE, h.public, 0, TTL).unwrap();
    let new_m1 = new_win.on_m0(&old_m0, 1).unwrap();
    let mut replay_m2 = old_m2.clone();
    replay_m2[5..21].copy_from_slice(&new_m1[5..21]); // even with the fresh pairing id
    assert_eq!(new_win.on_m2(&replay_m2, 2).err(), Some(Error::ConfirmationFailed));
    // Old helper messages replayed to a fresh Kadri attempt.
    let (mut new_kp, _) = KadriPairing::start(CODE, k.public, 0, TTL).unwrap();
    let _ = new_kp.on_m1(&old_m1, 1).unwrap();
    assert_eq!(new_kp.on_m3(&old_m3, 2).err(), Some(Error::ConfirmationFailed));
}

#[test]
fn timeouts_parallel_attempts_and_malformed_input() {
    let (k, h) = (Identity::generate().unwrap(), Identity::generate().unwrap());
    let mut win = HelperPairingWindow::open(CODE, h.public, 0, TTL).unwrap();
    let (_, m0) = KadriPairing::start(CODE, k.public, 0, TTL).unwrap();
    assert_eq!(win.on_m0(&m0, TTL + 1).err(), Some(Error::Expired));
    assert!(win.is_burned());

    let mut win = HelperPairingWindow::open(CODE, h.public, 0, TTL).unwrap();
    let (mut kp, m0) = KadriPairing::start(CODE, k.public, 0, TTL).unwrap();
    let m1 = win.on_m0(&m0, 1).unwrap();
    assert_eq!(win.on_m0(&m0, 2).err(), Some(Error::WrongState));
    assert_eq!(kp.on_m1(&m1, TTL + 1).err(), Some(Error::Expired));

    let mut win = HelperPairingWindow::open(CODE, h.public, 0, TTL).unwrap();
    assert_eq!(win.on_m0(b"KHP1\x01short", 1).err(), Some(Error::Malformed));
    assert!(win.is_burned());
    for bad in ["1234567", "123456789", "1234567a", "１２３４５６７８", " 1234567"] {
        assert!(KadriPairing::start(bad, k.public, 0, TTL).is_err(), "{bad}");
    }
    let c = HelperPairingWindow::generate_code().unwrap();
    assert!(c.len() == 8 && c.bytes().all(|b| b.is_ascii_digit()));
}

// ---------------------------------------------------------------- channel

fn connect(k: &Identity, pinned_helper: &[u8; 32], h: &Identity, pinned_kadri: Option<[u8; 32]>, reg: [u8; 16]) -> Result<(Channel, Channel), (Error, &'static str)> {
    let (init, hs1) = Initiator::start(k, pinned_helper, &reg).unwrap();
    assert_eq!(hs1.len(), HS_LEN);
    let (hchan, hs2) = channel::respond(h, &hs1, |id| if *id == reg { pinned_kadri } else { None }).map_err(|e| (e, "helper"))?;
    let kchan = init.finish(&hs2).map_err(|e| (e, "kadri"))?;
    Ok((kchan, hchan))
}

fn paired() -> (Identity, Identity, [u8; 16]) {
    let (k, h) = (Identity::generate().unwrap(), Identity::generate().unwrap());
    let r = pair(&k, &h, CODE, CODE).unwrap();
    (k, h, r.kadri.registration_id)
}

#[test]
fn channel_carries_bounded_media_records_both_ways() {
    let (k, h, reg) = paired();
    let (mut kc, mut hc) = connect(&k, &h.public, &h, Some(k.public), reg).unwrap();
    let ctrl = AppRecord::Control(br#"{"op":"download","u":"https://media.invalid/x"}"#.to_vec());
    let got = AppRecord::decode(&hc.open(&kc.seal(&ctrl.encode().unwrap()).unwrap()).unwrap()).unwrap();
    assert_eq!(got, ctrl);
    let media = AppRecord::Media { transfer: 7, offset: 1 << 33, data: vec![0xA5; MAX_MEDIA] };
    let enc = media.encode().unwrap();
    assert!(enc.len() <= MAX_PLAINTEXT);
    let rec = hc.seal(&enc).unwrap();
    assert!(rec.len() - 21 <= 65535);
    assert_eq!(AppRecord::decode(&kc.open(&rec).unwrap()).unwrap(), media);
    assert!(AppRecord::Media { transfer: 7, offset: 0, data: vec![0; MAX_MEDIA + 1] }.encode().is_err());
    assert_eq!(kc.seal(&vec![0; MAX_PLAINTEXT + 1]).err(), Some(Error::TooLarge));
    assert!(kc.seal(&vec![0; MAX_PLAINTEXT]).is_ok());
}

#[test]
fn foreign_listener_and_wrong_pins_are_rejected() {
    let (k, h, reg) = paired();
    let rogue = Identity::generate().unwrap();
    // Rogue listener knows Kadri's public key and registration id but not the helper's private key.
    let (init, hs1) = Initiator::start(&k, &h.public, &reg).unwrap();
    assert!(channel::respond(&rogue, &hs1, |_| Some(k.public)).is_err());
    // Rogue answers with its own well-formed responder message.
    let (init2, hs1b) = Initiator::start(&k, &h.public, &reg).unwrap();
    let rogue_view = Initiator::start(&k, &rogue.public, &reg).unwrap();
    let (_, rogue_hs2) = channel::respond(&rogue, &rogue_view.1, |_| Some(k.public)).unwrap();
    assert_eq!(init2.finish(&rogue_hs2).err(), Some(Error::AuthFailed));
    let _ = hs1b;
    // Forged random reply.
    let mut junk = b"KHC1\x11".to_vec();
    junk.extend_from_slice(&[7u8; HS_LEN - 5]);
    assert_eq!(init.finish(&junk).err(), Some(Error::AuthFailed));
    // Kadri pinned a wrong helper key: the genuine helper rejects msg1.
    assert_eq!(connect(&k, &rogue.public, &h, Some(k.public), reg).err().map(|e| e.0), Some(Error::AuthFailed));
    // Helper pinned a different Kadri key (e.g. other browser): rejected.
    assert_eq!(connect(&k, &h.public, &h, Some(rogue.public), reg).err().map(|e| e.0), Some(Error::AuthFailed));
    // Revoked / unknown registration.
    assert_eq!(connect(&k, &h.public, &h, None, reg).err().map(|e| e.0), Some(Error::AuthFailed));
}

#[test]
fn tampering_replay_reorder_and_reflection_are_rejected() {
    let (k, h, reg) = paired();
    let (mut kc, mut hc) = connect(&k, &h.public, &h, Some(k.public), reg).unwrap();
    let r1 = kc.seal(b"one").unwrap();
    let r2 = kc.seal(b"two").unwrap();
    assert_eq!(kc.open(&r1).err(), Some(Error::AuthFailed), "reflection");
    let (mut kc, mut hc2) = connect(&k, &h.public, &h, Some(k.public), reg).unwrap();
    let a = kc.seal(b"a").unwrap();
    let b = kc.seal(b"b").unwrap();
    assert_eq!(hc2.open(&b).err(), Some(Error::AuthFailed), "reorder");
    assert_eq!(hc2.open(&a).err(), Some(Error::WrongState), "channel is dead after failure");
    assert_eq!(hc.open(&r1).unwrap(), b"one");
    assert_eq!(hc.open(&r1).err(), Some(Error::AuthFailed), "replay");
    let _ = r2;
    let (mut kc, mut hc) = connect(&k, &h.public, &h, Some(k.public), reg).unwrap();
    let mut t = kc.seal(b"payload").unwrap();
    let last = t.len() - 1;
    t[last] ^= 1;
    assert_eq!(hc.open(&t).err(), Some(Error::AuthFailed), "tamper");
}

#[test]
fn restart_uses_fresh_session_state() {
    let (k, h, reg) = paired();
    let (mut kc1, _hc1) = connect(&k, &h.public, &h, Some(k.public), reg).unwrap();
    let old = kc1.seal(b"before restart").unwrap();
    // Helper process restarts: all transport state is gone, pins remain.
    let (_kc2, mut hc2) = connect(&k, &h.public, &h, Some(k.public), reg).unwrap();
    assert_ne!(kc1.session_id(), hc2.session_id());
    let mut moved = old.clone();
    moved[5..21].copy_from_slice(&hc2.session_id());
    assert_eq!(hc2.open(&moved).err(), Some(Error::AuthFailed));
}

#[test]
fn prologue_or_protocol_mismatch_fails_closed() {
    let (k, h, reg) = paired();
    let (init, _) = Initiator::start(&k, &h.public, &reg).unwrap();
    // A peer speaking a different prologue (e.g. a downgraded/older protocol).
    let mut other = snow::Builder::new("Noise_KK_25519_ChaChaPoly_SHA256".parse().unwrap())
        .local_private_key(&h.private[..]).unwrap()
        .remote_public_key(&k.public).unwrap()
        .prologue(b"kadri-helper-channel-poc/0").unwrap()
        .build_initiator().unwrap();
    let mut buf = [0u8; 128];
    let n = other.write_message(&[], &mut buf).unwrap();
    let mut hs1 = b"KHC1\x10".to_vec();
    hs1.extend_from_slice(&reg);
    hs1.extend_from_slice(&buf[..n]);
    assert_eq!(channel::respond(&h, &hs1, |_| Some(k.public)).err(), Some(Error::AuthFailed));
    let _ = init;
}
