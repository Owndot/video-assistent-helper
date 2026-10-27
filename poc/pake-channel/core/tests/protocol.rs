//! Pairing and channel tests with throwaway identities and artificial codes.
mod common;
use common::*;
use kadri_pairing_poc::channel::{self, Channel, Initiator, HS_LEN, MAX_PLAINTEXT, REC_HEADER};
use kadri_pairing_poc::pairing::{code_from_u32, HelperPairingWindow, KadriPairing, CODE_REJECT_FROM};
use kadri_pairing_poc::record::{AppRecord, MAX_MEDIA};
use kadri_pairing_poc::{noise_prologue, validate_public_key, Error, Identity};

// ------------------------------------------------------------- pairing

#[test]
fn pairing_pins_both_keys_and_one_shared_id() {
    let (k, h) = ids();
    let (kp, hp) = pair(&k, &h, CODE, CODE).unwrap();
    assert_eq!(kp.peer_public, h.public);
    assert_eq!(hp.peer_public, k.public);
    assert_eq!(kp.registration_id, hp.registration_id);
}

#[test]
fn pairing_id_is_bound_in_both_directions() {
    let (k, h) = ids();
    // M1 id rewritten, M2 forwarded unchanged: helper sees a foreign id.
    let mut win = HelperPairingWindow::open(CODE, h.public, 0, TTL).unwrap();
    let (mut kp, m0) = KadriPairing::start(CODE, k.public, 0, TTL).unwrap();
    let mut m1 = win.on_m0(&m0, 1).unwrap();
    let original: Vec<u8> = m1[5..21].to_vec();
    m1[5] ^= 0x01;
    let mut m2 = kp.on_m1(&m1, 2).unwrap();
    assert_eq!(win.on_m2(&m2.clone(), 3).err(), Some(Error::Malformed));
    // Same, but the MITM restores the id in M2: confirmation covers the id.
    let mut win = HelperPairingWindow::open(CODE, h.public, 0, TTL).unwrap();
    let (mut kp, m0) = KadriPairing::start(CODE, k.public, 0, TTL).unwrap();
    let mut m1 = win.on_m0(&m0, 1).unwrap();
    let original2: Vec<u8> = m1[5..21].to_vec();
    m1[5] ^= 0x01;
    m2 = kp.on_m1(&m1, 2).unwrap();
    m2[5..21].copy_from_slice(&original2);
    assert_eq!(win.on_m2(&m2, 3).err(), Some(Error::ConfirmationFailed));
    let _ = original;
    // M3 id rewritten: Kadri rejects before pinning.
    let mut win = HelperPairingWindow::open(CODE, h.public, 0, TTL).unwrap();
    let (mut kp, m0) = KadriPairing::start(CODE, k.public, 0, TTL).unwrap();
    let m1 = win.on_m0(&m0, 1).unwrap();
    let m2 = kp.on_m1(&m1, 2).unwrap();
    let (_, mut m3) = win.on_m2(&m2, 3).unwrap();
    m3[20] ^= 0x80;
    assert_eq!(kp.on_m3(&m3, 4).err(), Some(Error::Malformed));
    // ID confusion between two concurrent pairings with the same code.
    let mut w1 = HelperPairingWindow::open(CODE, h.public, 0, TTL).unwrap();
    let mut w2 = HelperPairingWindow::open(CODE, h.public, 0, TTL).unwrap();
    let (mut k1, a0) = KadriPairing::start(CODE, k.public, 0, TTL).unwrap();
    let (_, b0) = KadriPairing::start(CODE, k.public, 0, TTL).unwrap();
    let a1 = w1.on_m0(&a0, 1).unwrap();
    let b1 = w2.on_m0(&b0, 1).unwrap();
    let mut mixed = a1.clone();
    mixed[5..21].copy_from_slice(&b1[5..21]); // id from window 2, share from window 1
    let m2 = k1.on_m1(&mixed, 2).unwrap();
    assert!(w1.on_m2(&m2, 3).is_err());
    assert_eq!(w2.on_m2(&m2, 3).err(), Some(Error::ConfirmationFailed));
}

#[test]
fn wrong_code_fails_and_burns_the_window() {
    let (k, h) = ids();
    let mut win = HelperPairingWindow::open(CODE, h.public, 0, TTL).unwrap();
    let (mut kp, m0) = KadriPairing::start(WRONG, k.public, 0, TTL).unwrap();
    let m1 = win.on_m0(&m0, 1).unwrap();
    let m2 = kp.on_m1(&m1, 2).unwrap();
    assert_eq!(win.on_m2(&m2, 3).err(), Some(Error::ConfirmationFailed));
    assert!(win.is_burned());
    let (_, m0b) = KadriPairing::start(CODE, k.public, 4, TTL).unwrap();
    assert_eq!(win.on_m0(&m0b, 5).err(), Some(Error::AttemptsExhausted));
}

#[test]
fn rogue_listener_with_guessed_code_is_rejected_by_kadri() {
    let (k, rogue) = ids();
    let mut fake = HelperPairingWindow::open(WRONG, rogue.public, 0, TTL).unwrap();
    let (mut kp, m0) = KadriPairing::start(CODE, k.public, 0, TTL).unwrap();
    let m1 = fake.on_m0(&m0, 1).unwrap();
    let m2 = kp.on_m1(&m1, 2).unwrap();
    let mut m3 = b"KHP2\x04".to_vec();
    m3.extend_from_slice(&m2[5..21]);
    m3.extend_from_slice(&[0x5a; 32]);
    assert_eq!(kp.on_m3(&m3, 3).err(), Some(Error::ConfirmationFailed));
    assert!(kp.is_terminal());
    assert_eq!(kp.on_m3(&m3, 3).err(), Some(Error::WrongState));
}

#[test]
fn identity_substitution_by_mitm_is_detected() {
    let (k, h) = ids();
    let evil = Identity::generate().unwrap();
    let mut win = HelperPairingWindow::open(CODE, h.public, 0, TTL).unwrap();
    let (mut kp, mut m0) = KadriPairing::start(CODE, k.public, 0, TTL).unwrap();
    m0[5..37].copy_from_slice(&evil.public);
    let m1 = win.on_m0(&m0, 1).unwrap();
    let m2 = kp.on_m1(&m1, 2).unwrap();
    assert_eq!(win.on_m2(&m2, 3).err(), Some(Error::ConfirmationFailed));

    let mut win = HelperPairingWindow::open(CODE, h.public, 0, TTL).unwrap();
    let (mut kp, m0) = KadriPairing::start(CODE, k.public, 0, TTL).unwrap();
    let mut m1 = win.on_m0(&m0, 1).unwrap();
    m1[21..53].copy_from_slice(&evil.public);
    let m2 = kp.on_m1(&m1, 2).unwrap();
    assert_eq!(win.on_m2(&m2, 3).err(), Some(Error::ConfirmationFailed));
}

#[test]
fn modified_pake_shares_are_detected() {
    let (k, h) = ids();
    let mut other = HelperPairingWindow::open(CODE, h.public, 0, TTL).unwrap();
    let (_, m0x) = KadriPairing::start(CODE, k.public, 0, TTL).unwrap();
    let foreign_m1 = other.on_m0(&m0x, 1).unwrap();
    let mut win = HelperPairingWindow::open(CODE, h.public, 0, TTL).unwrap();
    let (mut kp, m0) = KadriPairing::start(CODE, k.public, 0, TTL).unwrap();
    let mut m1 = win.on_m0(&m0, 1).unwrap();
    m1[53..].copy_from_slice(&foreign_m1[53..]);
    let m2 = kp.on_m1(&m1, 2).unwrap();
    assert_eq!(win.on_m2(&m2, 3).err(), Some(Error::ConfirmationFailed));
    let (mut kp, m0) = KadriPairing::start(CODE, k.public, 0, TTL).unwrap();
    let mut win = HelperPairingWindow::open(CODE, h.public, 0, TTL).unwrap();
    let mut m1 = win.on_m0(&m0, 1).unwrap();
    m1[54] ^= 0x01;
    assert!(kp.on_m1(&m1, 2).is_err());
    assert!(kp.is_terminal());
}

#[test]
fn reflected_and_replayed_pairing_messages_are_rejected() {
    let (k, h) = ids();
    let mut win = HelperPairingWindow::open(CODE, h.public, 0, TTL).unwrap();
    let (mut kp, m0) = KadriPairing::start(CODE, k.public, 0, TTL).unwrap();
    let m1 = win.on_m0(&m0, 1).unwrap();
    let m2 = kp.on_m1(&m1, 2).unwrap();
    let mut echoed = m2[..5 + 16 + 32].to_vec();
    echoed[4] = 4;
    assert!(kp.on_m3(&echoed, 3).is_err());
    let mut win2 = HelperPairingWindow::open(CODE, h.public, 0, TTL).unwrap();
    let _ = win2.on_m0(&m0, 1).unwrap();
    assert!(win2.on_m2(&m1, 2).is_err());

    let mut old_win = HelperPairingWindow::open(CODE, h.public, 0, TTL).unwrap();
    let (mut old_kp, old_m0) = KadriPairing::start(CODE, k.public, 0, TTL).unwrap();
    let old_m1 = old_win.on_m0(&old_m0, 1).unwrap();
    let old_m2 = old_kp.on_m1(&old_m1, 2).unwrap();
    let (_, old_m3) = old_win.on_m2(&old_m2, 3).unwrap();
    let mut new_win = HelperPairingWindow::open(CODE, h.public, 0, TTL).unwrap();
    let new_m1 = new_win.on_m0(&old_m0, 1).unwrap();
    let mut replay_m2 = old_m2.clone();
    replay_m2[5..21].copy_from_slice(&new_m1[5..21]);
    assert_eq!(new_win.on_m2(&replay_m2, 2).err(), Some(Error::ConfirmationFailed));
    let (mut new_kp, _) = KadriPairing::start(CODE, k.public, 0, TTL).unwrap();
    let _ = new_kp.on_m1(&old_m1, 1).unwrap();
    assert_eq!(new_kp.on_m3(&old_m3, 2).err(), Some(Error::ConfirmationFailed));
}

/// Each phase accepts exactly up to its deadline and is terminal after it.
#[test]
fn deadlines_in_every_pairing_phase() {
    let (k, h) = ids();
    let d = TTL; // both flows start at t=0
    // helper on_m0
    let mut w = HelperPairingWindow::open(CODE, h.public, 0, TTL).unwrap();
    let (_, m0) = KadriPairing::start(CODE, k.public, 0, TTL).unwrap();
    assert_eq!(w.on_m0(&m0, d + 1).err(), Some(Error::Expired));
    assert!(w.is_burned());
    let mut w = HelperPairingWindow::open(CODE, h.public, 0, TTL).unwrap();
    assert!(w.on_m0(&m0, d).is_ok());
    // kadri on_m1
    let mut w = HelperPairingWindow::open(CODE, h.public, 0, TTL).unwrap();
    let (mut kp, m0) = KadriPairing::start(CODE, k.public, 0, TTL).unwrap();
    let m1 = w.on_m0(&m0, 1).unwrap();
    assert_eq!(kp.on_m1(&m1, d + 1).err(), Some(Error::Expired));
    assert!(kp.is_terminal());
    // helper on_m2
    let mut w = HelperPairingWindow::open(CODE, h.public, 0, TTL).unwrap();
    let (mut kp, m0) = KadriPairing::start(CODE, k.public, 0, TTL).unwrap();
    let m1 = w.on_m0(&m0, 1).unwrap();
    let m2 = kp.on_m1(&m1, d).unwrap();
    assert_eq!(w.on_m2(&m2, d + 1).err(), Some(Error::Expired));
    assert!(w.is_burned());
    // kadri on_m3
    let mut w = HelperPairingWindow::open(CODE, h.public, 0, TTL).unwrap();
    let (mut kp, m0) = KadriPairing::start(CODE, k.public, 0, TTL).unwrap();
    let m1 = w.on_m0(&m0, 1).unwrap();
    let m2 = kp.on_m1(&m1, 2).unwrap();
    let (_, m3) = w.on_m2(&m2, d).unwrap();
    assert_eq!(kp.on_m3(&m3, d + 1).err(), Some(Error::Expired));
    assert!(kp.is_terminal());
    // the boundary itself is still accepted in the last phase
    let mut w = HelperPairingWindow::open(CODE, h.public, 0, TTL).unwrap();
    let (mut kp, m0) = KadriPairing::start(CODE, k.public, 0, TTL).unwrap();
    let m1 = w.on_m0(&m0, 1).unwrap();
    let m2 = kp.on_m1(&m1, 2).unwrap();
    let (_, m3) = w.on_m2(&m2, d).unwrap();
    assert!(kp.on_m3(&m3, d).is_ok());
}

#[test]
fn parallel_and_repeated_attempts() {
    let (k, h) = ids();
    let mut w = HelperPairingWindow::open(CODE, h.public, 0, TTL).unwrap();
    let (mut kp, m0) = KadriPairing::start(CODE, k.public, 0, TTL).unwrap();
    let m1 = w.on_m0(&m0, 1).unwrap();
    // A second M0 while the first is pending is refused and does not disturb it.
    let (_, other) = KadriPairing::start(CODE, Identity::generate().unwrap().public, 0, TTL).unwrap();
    assert_eq!(w.on_m0(&other, 2).err(), Some(Error::WrongState));
    let m2 = kp.on_m1(&m1, 3).unwrap();
    assert!(w.on_m2(&m2, 4).is_ok());
    assert_eq!(w.on_m0(&m0, 5).err(), Some(Error::AttemptsExhausted));
    assert_eq!(w.on_m2(&m2, 5).err(), Some(Error::AttemptsExhausted));
}

#[test]
fn invalid_or_low_order_keys_are_rejected() {
    let (k, h) = ids();
    for bad in BAD_KEYS {
        let key = hex32(bad);
        assert_eq!(validate_public_key(&key), Err(Error::InvalidKey), "{bad}");
        assert!(KadriPairing::start(CODE, key, 0, TTL).is_err());
        assert!(HelperPairingWindow::open(CODE, key, 0, TTL).is_err());
        // Injected by a MITM into M0 / M1.
        let mut w = HelperPairingWindow::open(CODE, h.public, 0, TTL).unwrap();
        let (mut kp, mut m0) = KadriPairing::start(CODE, k.public, 0, TTL).unwrap();
        let good_m0 = m0.clone();
        m0[5..37].copy_from_slice(&key);
        assert_eq!(w.on_m0(&m0, 1).err(), Some(Error::InvalidKey));
        assert!(w.is_burned());
        let mut w = HelperPairingWindow::open(CODE, h.public, 0, TTL).unwrap();
        let mut m1 = w.on_m0(&good_m0, 1).unwrap();
        m1[21..53].copy_from_slice(&key);
        assert_eq!(kp.on_m1(&m1, 2).err(), Some(Error::InvalidKey));
    }
    // A MITM reflects Kadri's own key as the helper identity in M1.
    let mut w = HelperPairingWindow::open(CODE, h.public, 0, TTL).unwrap();
    let (mut kp, m0) = KadriPairing::start(CODE, k.public, 0, TTL).unwrap();
    let mut m1 = w.on_m0(&m0, 1).unwrap();
    m1[21..53].copy_from_slice(&k.public);
    assert_eq!(kp.on_m1(&m1, 2).err(), Some(Error::InvalidKey));
    // High bit set on an otherwise valid key (non-canonical encoding).
    let mut hb = k.public;
    hb[31] |= 0x80;
    assert_eq!(validate_public_key(&hb), Err(Error::InvalidKey));
    // Reflection of the peer's own key as the other identity.
    let mut w = HelperPairingWindow::open(CODE, h.public, 0, TTL).unwrap();
    let (_, m0) = KadriPairing::start(CODE, h.public, 0, TTL).unwrap();
    assert_eq!(w.on_m0(&m0, 1).err(), Some(Error::InvalidKey));
    for _ in 0..32 {
        assert!(validate_public_key(&Identity::generate().unwrap().public).is_ok());
    }
}

#[test]
fn malformed_input_and_code_format() {
    let (k, h) = ids();
    let mut win = HelperPairingWindow::open(CODE, h.public, 0, TTL).unwrap();
    assert_eq!(win.on_m0(b"KHP2\x01short", 1).err(), Some(Error::Malformed));
    assert!(win.is_burned());
    let mut win = HelperPairingWindow::open(CODE, h.public, 0, TTL).unwrap();
    let (_, mut m0) = KadriPairing::start(CODE, k.public, 0, TTL).unwrap();
    m0[3] = b'1'; // previous protocol version
    assert_eq!(win.on_m0(&m0, 1).err(), Some(Error::Malformed));
    for bad in ["1234567", "123456789", "1234567a", "１２３４５６７８", " 1234567", ""] {
        assert!(KadriPairing::start(bad, k.public, 0, TTL).is_err(), "{bad:?}");
        assert!(HelperPairingWindow::open(bad, h.public, 0, TTL).is_err(), "{bad:?}");
    }
}

#[test]
fn code_generation_bounds_and_distribution() {
    assert_eq!(CODE_REJECT_FROM, 4_200_000_000);
    assert_eq!(code_from_u32(0).as_deref(), Some("00000000"));
    assert_eq!(code_from_u32(12_345_678).as_deref(), Some("12345678"));
    assert_eq!(code_from_u32(100_000_000).as_deref(), Some("00000000"));
    assert_eq!(code_from_u32(CODE_REJECT_FROM - 1).as_deref(), Some("99999999"));
    assert_eq!(code_from_u32(CODE_REJECT_FROM), None);
    assert_eq!(code_from_u32(u32::MAX), None);
    // Coarse sanity check of the generator, not a randomness proof:
    // each leading digit within +-10 % of 1/10 over 20 000 draws.
    let mut first = [0u32; 10];
    for _ in 0..20_000 {
        let c = HelperPairingWindow::generate_code().unwrap();
        assert!(c.len() == 8 && c.bytes().all(|b| b.is_ascii_digit()));
        first[(c.as_bytes()[0] - b'0') as usize] += 1;
    }
    assert!(first.iter().all(|&n| (1800..=2200).contains(&n)), "{first:?}");
}

// ------------------------------------------------------------- channel

fn connect(k: &Identity, pinned_helper: &[u8; 32], h: &Identity, pinned_kadri: Option<[u8; 32]>, reg: [u8; 16]) -> Result<(Channel, Channel), (Error, &'static str)> {
    let (mut init, hs1) = Initiator::start(k, pinned_helper, &reg).unwrap();
    assert_eq!(hs1.len(), HS_LEN);
    let (hchan, hs2) = channel::respond(h, &hs1, |id| if *id == reg { pinned_kadri } else { None }).map_err(|e| (e, "helper"))?;
    let kchan = init.finish(&hs2).map_err(|e| (e, "kadri"))?;
    Ok((kchan, hchan))
}

fn paired() -> (Identity, Identity, [u8; 16]) {
    let (k, h) = ids();
    let (kp, _) = pair(&k, &h, CODE, CODE).unwrap();
    (k, h, kp.registration_id)
}

#[test]
fn channel_carries_bounded_media_records_both_ways() {
    let (k, h, reg) = paired();
    let (mut kc, mut hc) = connect(&k, &h.public, &h, Some(k.public), reg).unwrap();
    let ctrl = AppRecord::Control(br#"{"op":"download","u":"https://media.invalid/x"}"#.to_vec());
    assert_eq!(hc.open_app(&kc.seal_app(&ctrl).unwrap()).unwrap(), ctrl);
    let media = AppRecord::Media { transfer: 7, offset: 1 << 33, data: vec![0xA5; MAX_MEDIA] };
    let rec = hc.seal_app(&media).unwrap();
    assert!(rec.len() - REC_HEADER <= 65535);
    assert_eq!(kc.open_app(&rec).unwrap(), media);
    let end = AppRecord::End { transfer: 7, total: 1 << 34 };
    assert_eq!(kc.open_app(&hc.seal_app(&end).unwrap()).unwrap(), end);
    assert!(kc.seal(&vec![0; MAX_PLAINTEXT]).is_ok());
    assert_eq!(hc.open(&kc.seal(&vec![1; 10]).unwrap()).err(), Some(Error::AuthFailed), "skipped one record");
}

#[test]
fn oversized_seal_is_terminal() {
    let (k, h, reg) = paired();
    let (mut kc, _) = connect(&k, &h.public, &h, Some(k.public), reg).unwrap();
    assert_eq!(kc.seal(&vec![0; MAX_PLAINTEXT + 1]).err(), Some(Error::TooLarge));
    assert!(kc.is_dead());
    assert_eq!(kc.seal(b"x").err(), Some(Error::WrongState));
    let (mut kc, _) = connect(&k, &h.public, &h, Some(k.public), reg).unwrap();
    assert_eq!(kc.seal_app(&AppRecord::Media { transfer: 1, offset: 0, data: vec![0; MAX_MEDIA + 1] }).err(), Some(Error::TooLarge));
    assert!(kc.is_dead());
}

#[test]
fn handshake_payloads_must_be_empty() {
    let (k, h, reg) = paired();
    let prologue = noise_prologue(&reg);
    // Initiator with the right keys and prologue but a non-empty payload.
    let mut hs = snow::Builder::new(kadri_pairing_poc::NOISE_PARAMS.parse().unwrap())
        .local_private_key(&k.private[..]).unwrap().remote_public_key(&h.public).unwrap()
        .prologue(&prologue).unwrap().build_initiator().unwrap();
    let mut buf = [0u8; 256];
    let n = hs.write_message(b"early data", &mut buf).unwrap();
    let mut hs1 = b"KHC2\x10".to_vec();
    hs1.extend_from_slice(&reg);
    hs1.extend_from_slice(&buf[..n]);
    assert_eq!(channel::respond(&h, &hs1, |_| Some(k.public)).err(), Some(Error::Malformed));
    // Responder with a non-empty payload in msg2.
    let (mut init, good_hs1) = Initiator::start(&k, &h.public, &reg).unwrap();
    let mut resp = snow::Builder::new(kadri_pairing_poc::NOISE_PARAMS.parse().unwrap())
        .local_private_key(&h.private[..]).unwrap().remote_public_key(&k.public).unwrap()
        .prologue(&prologue).unwrap().build_responder().unwrap();
    let mut p = [0u8; 64];
    resp.read_message(&good_hs1[REC_HEADER..], &mut p).unwrap();
    let n = resp.write_message(b"x", &mut buf).unwrap();
    let mut hs2 = b"KHC2\x11".to_vec();
    hs2.extend_from_slice(&[9; 16]);
    hs2.extend_from_slice(&buf[..n]);
    assert_eq!(init.finish(&hs2).err(), Some(Error::Malformed));
    assert_eq!(init.finish(&hs2).err(), Some(Error::WrongState), "initiator is single use");
}

#[test]
fn foreign_listener_and_wrong_pins_are_rejected() {
    let (k, h, reg) = paired();
    let rogue = Identity::generate().unwrap();
    let (mut init, hs1) = Initiator::start(&k, &h.public, &reg).unwrap();
    assert!(channel::respond(&rogue, &hs1, |_| Some(k.public)).is_err());
    let (mut init2, _) = Initiator::start(&k, &h.public, &reg).unwrap();
    let rogue_view = Initiator::start(&k, &rogue.public, &reg).unwrap();
    let (_, rogue_hs2) = channel::respond(&rogue, &rogue_view.1, |_| Some(k.public)).unwrap();
    assert_eq!(init2.finish(&rogue_hs2).err(), Some(Error::AuthFailed));
    let mut junk = b"KHC2\x11".to_vec();
    junk.extend_from_slice(&[7u8; HS_LEN - 5]);
    assert_eq!(init.finish(&junk).err(), Some(Error::AuthFailed));
    assert_eq!(connect(&k, &rogue.public, &h, Some(k.public), reg).err().map(|e| e.0), Some(Error::AuthFailed));
    assert_eq!(connect(&k, &h.public, &h, Some(rogue.public), reg).err().map(|e| e.0), Some(Error::AuthFailed));
    assert_eq!(connect(&k, &h.public, &h, None, reg).err().map(|e| e.0), Some(Error::AuthFailed));
}

#[test]
fn prologue_binds_version_and_registration_id() {
    let (k, h, reg) = paired();
    let mut other = snow::Builder::new(kadri_pairing_poc::NOISE_PARAMS.parse().unwrap())
        .local_private_key(&k.private[..]).unwrap().remote_public_key(&h.public).unwrap()
        .prologue(b"kadri-helper-channel-poc/1").unwrap().build_initiator().unwrap();
    let mut buf = [0u8; 128];
    let n = other.write_message(&[], &mut buf).unwrap();
    let mut hs1 = b"KHC2\x10".to_vec();
    hs1.extend_from_slice(&reg);
    hs1.extend_from_slice(&buf[..n]);
    assert_eq!(channel::respond(&h, &hs1, |_| Some(k.public)).err(), Some(Error::AuthFailed));
    // Two registrations of the same Kadri key: swapping the cleartext id fails.
    let (kp2, _) = pair(&k, &h, CODE, CODE).unwrap();
    let reg2 = kp2.registration_id;
    let (_, mut hs1) = Initiator::start(&k, &h.public, &reg).unwrap();
    hs1[5..21].copy_from_slice(&reg2);
    assert_eq!(channel::respond(&h, &hs1, |id| if *id == reg || *id == reg2 { Some(k.public) } else { None }).err(), Some(Error::AuthFailed));
}

/// Every protocol error kills the channel; the next genuine record from the
/// peer is then refused without touching the CipherState.
#[test]
fn every_receive_error_is_terminal() {
    let (k, h, reg) = paired();
    type Mangle = fn(&mut Vec<u8>, &mut Channel) -> Vec<u8>;
    let cases: Vec<(&str, Mangle, Error)> = vec![
        ("wrong session id", |r, _| { r[5] ^= 1; r.clone() }, Error::Malformed),
        ("bad magic", |r, _| { r[0] = b'X'; r.clone() }, Error::Malformed),
        ("bad type", |r, _| { r[4] = 0x10; r.clone() }, Error::Malformed),
        ("header only", |r, _| r[..REC_HEADER].to_vec(), Error::Malformed),
        ("shorter than tag", |r, _| r[..REC_HEADER + 15].to_vec(), Error::Malformed),
        ("truncated ciphertext", |r, _| r[..r.len() - 1].to_vec(), Error::AuthFailed),
        ("flipped bit", |r, _| { let l = r.len() - 1; r[l] ^= 1; r.clone() }, Error::AuthFailed),
        ("oversized", |r, _| { let mut v = r.clone(); v.resize(REC_HEADER + 65536, 0); v }, Error::TooLarge),
        ("reflection of own record", |_, me| me.seal(b"\x01\x00\x00").unwrap(), Error::AuthFailed),
        ("authenticated garbage app record", |_, _| Vec::new(), Error::Malformed),
    ];
    for (name, mangle, want) in cases {
        let (mut kc, mut hc) = connect(&k, &h.public, &h, Some(k.public), reg).unwrap();
        let bad = if name == "authenticated garbage app record" {
            hc.seal(&[9, 9, 9]).unwrap()
        } else {
            let mut first = hc.seal_app(&AppRecord::Control(b"{}".to_vec())).unwrap();
            mangle(&mut first, &mut kc)
        };
        let next = hc.seal_app(&AppRecord::Control(b"{\"n\":2}".to_vec())).unwrap();
        assert_eq!(kc.open_app(&bad).err(), Some(want), "{name}");
        assert!(kc.is_dead(), "{name}");
        assert_eq!(kc.open_app(&next).err(), Some(Error::WrongState), "{name}: no continuation");
        assert_eq!(kc.seal(b"x").err(), Some(Error::WrongState), "{name}: no further nonce use");
    }
}

#[test]
fn replay_and_reorder_are_terminal() {
    let (k, h, reg) = paired();
    let (mut kc, mut hc) = connect(&k, &h.public, &h, Some(k.public), reg).unwrap();
    let a = kc.seal(b"\x01\x00\x00").unwrap();
    let b = kc.seal(b"\x01\x00\x00").unwrap();
    assert!(hc.open_app(&a).is_ok());
    assert_eq!(hc.open_app(&a).err(), Some(Error::AuthFailed), "replay");
    assert_eq!(hc.open_app(&b).err(), Some(Error::WrongState));
    let (mut kc, mut hc) = connect(&k, &h.public, &h, Some(k.public), reg).unwrap();
    let a = kc.seal(b"\x01\x00\x00").unwrap();
    let b = kc.seal(b"\x01\x00\x00").unwrap();
    assert_eq!(hc.open_app(&b).err(), Some(Error::AuthFailed), "reorder");
    assert_eq!(hc.open_app(&a).err(), Some(Error::WrongState));
    let (mut kc, _) = connect(&k, &h.public, &h, Some(k.public), reg).unwrap();
    kc.close();
    assert_eq!(kc.seal(b"x").err(), Some(Error::WrongState), "closed session");
}

#[test]
fn restart_uses_fresh_session_state() {
    let (k, h, reg) = paired();
    let (mut kc1, _hc1) = connect(&k, &h.public, &h, Some(k.public), reg).unwrap();
    let old = kc1.seal(b"\x01\x00\x00").unwrap();
    let (_kc2, mut hc2) = connect(&k, &h.public, &h, Some(k.public), reg).unwrap();
    assert_ne!(kc1.session_id(), hc2.session_id());
    let mut moved = old.clone();
    moved[5..21].copy_from_slice(&hc2.session_id());
    assert_eq!(hc2.open_app(&moved).err(), Some(Error::AuthFailed));
}
