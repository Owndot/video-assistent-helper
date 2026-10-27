//! Helper session table: HS1 replay, limits, timeouts, registrations.
mod common;
use common::*;
use kadri_pairing_poc::channel::{Channel, Initiator};
use kadri_pairing_poc::host::{HelperHost, Limits};
use kadri_pairing_poc::pairing::Pinned;
use kadri_pairing_poc::record::AppRecord;
use kadri_pairing_poc::{Error, Identity};

fn host_with(k: &Identity, limits: Limits) -> (HelperHost, [u8; 32], [u8; 16], Identity) {
    let h = Identity::generate().unwrap();
    let hp = h.public;
    let (kp, hpin) = pair(k, &h, CODE, CODE).unwrap();
    let mut host = HelperHost::new(h, limits);
    host.add_pending(&hpin, 0).unwrap();
    (host, hp, kp.registration_id, Identity::from_private(&k.private[..]).unwrap())
}

fn connect(host: &mut HelperHost, k: &Identity, hp: &[u8; 32], reg: [u8; 16], now: u64) -> (Channel, Vec<u8>) {
    let (mut init, hs1) = Initiator::start(k, hp, &reg).unwrap();
    let hs2 = host.handshake(&hs1, now).unwrap();
    (init.finish(&hs2).unwrap(), hs1)
}

fn ping(host: &mut HelperHost, ch: &mut Channel, now: u64) -> Result<(), Error> {
    let rec = ch.seal_app(&AppRecord::Control(b"{\"op\":\"ping\"}".to_vec()))?;
    let (sid, _) = host.open(&rec, now)?;
    let reply = host.seal(&sid, &AppRecord::Control(b"{\"ok\":true}".to_vec()), now)?;
    ch.open_app(&reply).map(|_| ())
}

#[test]
fn replayed_hs1_never_displaces_established_sessions() {
    let k = Identity::generate().unwrap();
    let (mut host, hp, reg, _) = host_with(&k, Limits::default());
    let (mut ch, hs1) = connect(&mut host, &k, &hp, reg, 1);
    ping(&mut host, &mut ch, 2).unwrap();
    for t in 0..200 {
        // The attacker replays the recorded HS1 and gets a valid-looking HS2...
        let hs2 = host.handshake(&hs1, 3 + t).unwrap();
        // ...but cannot produce a record for that half-open session.
        let mut forged = b"KHC2\x20".to_vec();
        forged.extend_from_slice(&hs2[5..21]);
        forged.extend_from_slice(&[0u8; 32]);
        assert_eq!(host.open(&forged, 3 + t).err(), Some(Error::AuthFailed));
    }
    for t in 0..200 {
        host.handshake(&hs1, 300 + t).unwrap();
    }
    let st = host.stats();
    assert!(st.half_open <= Limits::default().max_half_open_per_registration, "{st:?}");
    assert_eq!(st.established, 1);
    ping(&mut host, &mut ch, 600).unwrap();
}

#[test]
fn half_open_sessions_expire_and_are_bounded_globally() {
    let k = Identity::generate().unwrap();
    let l = Limits::default();
    let (mut host, hp, reg, _) = host_with(&k, l);
    let (_, hs1) = connect(&mut host, &k, &hp, reg, 0);
    host.handshake(&hs1, 1).unwrap();
    assert_eq!(host.stats().half_open, 2);
    host.sweep(l.handshake_ttl_ms + 2);
    assert_eq!(host.stats().half_open, 0);
    // A legitimate handshake that arrives too late cannot be promoted.
    let (mut late, _) = connect(&mut host, &k, &hp, reg, 0);
    assert_eq!(ping(&mut host, &mut late, l.handshake_ttl_ms + 1).err(), Some(Error::UnknownSession));
}

#[test]
fn established_limits_and_idle_and_age_timeouts() {
    let k = Identity::generate().unwrap();
    let l = Limits::default();
    let (mut host, hp, reg, _) = host_with(&k, l);
    let (mut a, _) = connect(&mut host, &k, &hp, reg, 0);
    ping(&mut host, &mut a, 1).unwrap();
    let (mut b, _) = connect(&mut host, &k, &hp, reg, 2);
    ping(&mut host, &mut b, 3).unwrap();
    // Third authenticated session of the same registration evicts the oldest.
    let (mut c, _) = connect(&mut host, &k, &hp, reg, 4);
    ping(&mut host, &mut c, 5).unwrap();
    assert_eq!(host.stats().established, l.max_established_per_registration);
    assert_eq!(ping(&mut host, &mut a, 6).err(), Some(Error::UnknownSession));
    ping(&mut host, &mut b, 7).unwrap();
    // Idle timeout.
    assert_eq!(ping(&mut host, &mut b, 7 + l.idle_ttl_ms + 1).err(), Some(Error::UnknownSession));
    // Absolute age limit even when active.
    let (mut d, _) = connect(&mut host, &k, &hp, reg, 10);
    ping(&mut host, &mut d, 11).unwrap();
    let mut t = 11;
    while t < 10 + l.max_session_ms {
        t += l.idle_ttl_ms / 2;
        if t > 10 + l.max_session_ms { break; }
        ping(&mut host, &mut d, t).unwrap();
    }
    assert_eq!(ping(&mut host, &mut d, 10 + l.max_session_ms + 1).err(), Some(Error::UnknownSession));
}

#[test]
fn record_errors_remove_only_that_session() {
    let k = Identity::generate().unwrap();
    let (mut host, hp, reg, _) = host_with(&k, Limits::default());
    let (mut a, _) = connect(&mut host, &k, &hp, reg, 0);
    ping(&mut host, &mut a, 1).unwrap();
    let (mut b, _) = connect(&mut host, &k, &hp, reg, 2);
    ping(&mut host, &mut b, 3).unwrap();
    let mut bad = b.seal(b"\x01\x00\x00").unwrap();
    let last = bad.len() - 1;
    bad[last] ^= 1;
    assert_eq!(host.open(&bad, 4).err(), Some(Error::AuthFailed));
    assert_eq!(host.stats().established, 1);
    ping(&mut host, &mut a, 5).unwrap();
    // Unknown session ids change nothing.
    let mut unknown = b"KHC2\x20".to_vec();
    unknown.extend_from_slice(&[0xee; 16]);
    unknown.extend_from_slice(&[0; 40]);
    assert_eq!(host.open(&unknown, 6).err(), Some(Error::UnknownSession));
    ping(&mut host, &mut a, 7).unwrap();
}

#[test]
fn pending_registration_expires_when_m3_was_lost() {
    let k = Identity::generate().unwrap();
    let l = Limits::default();
    let (mut host, _, _, _) = host_with(&k, l);
    assert!(!host.registrations()[0].confirmed);
    host.sweep(l.pending_ttl_ms + 1);
    assert_eq!(host.stats().registrations, 0);
}

#[test]
fn first_session_confirms_and_retires_older_registrations_of_same_key() {
    let k = Identity::generate().unwrap();
    let (mut host, hp, reg1, _) = host_with(&k, Limits::default());
    let (mut a, _) = connect(&mut host, &k, &hp, reg1, 1);
    ping(&mut host, &mut a, 2).unwrap();
    assert!(host.registrations()[0].confirmed);
    // Re-pairing of the same browser identity creates a second registration.
    let reg2 = [0x42; 16];
    host.add_pending(&Pinned { registration_id: reg2, peer_public: k.public }, 3).unwrap();
    assert_eq!(host.stats().registrations, 2);
    let (mut b, _) = connect(&mut host, &k, &hp, reg2, 4);
    ping(&mut host, &mut b, 5).unwrap();
    // First session of the new registration is only Kadri's confirmation:
    // the old registration and its session stay until Kadri commits (R4).
    assert_eq!(host.stats().registrations, 2);
    ping(&mut host, &mut a, 6).unwrap();
    // Kadri committed to reg2: its next session retires the old registration.
    let (mut c, _) = connect(&mut host, &k, &hp, reg2, 7);
    ping(&mut host, &mut c, 8).unwrap();
    assert_eq!(host.stats().registrations, 1);
    assert_eq!(host.registrations()[0].id, reg2);
    assert_eq!(ping(&mut host, &mut a, 9).err(), Some(Error::UnknownSession), "old registration's sessions are gone");
}

#[test]
fn registry_capacity_and_revocation() {
    let k = Identity::generate().unwrap();
    let l = Limits::default();
    let (mut host, hp, reg, _) = host_with(&k, l);
    for i in 1..l.max_registrations {
        host.add_pending(&Pinned { registration_id: [i as u8; 16], peer_public: Identity::generate().unwrap().public }, 1).unwrap();
    }
    assert!(!host.can_register(2));
    assert_eq!(host.add_pending(&Pinned { registration_id: [0x77; 16], peer_public: Identity::generate().unwrap().public }, 2).err(), Some(Error::RegistryFull));
    let (mut a, _) = connect(&mut host, &k, &hp, reg, 3);
    ping(&mut host, &mut a, 4).unwrap();
    host.revoke(&reg);
    assert_eq!(ping(&mut host, &mut a, 5).err(), Some(Error::UnknownSession), "revocation closes sessions");
    let (mut init, hs1) = Initiator::start(&k, &hp, &reg).unwrap();
    assert_eq!(host.handshake(&hs1, 6).err(), Some(Error::AuthFailed));
    let _ = init.finish(&[]);
    assert!(host.can_register(7));
    host.revoke_all();
    assert_eq!(host.stats().registrations, 0);
    // Duplicate id and invalid key are refused.
    host.add_pending(&Pinned { registration_id: reg, peer_public: k.public }, 8).unwrap();
    assert!(host.add_pending(&Pinned { registration_id: reg, peer_public: k.public }, 8).is_err());
    assert_eq!(host.add_pending(&Pinned { registration_id: [1; 16], peer_public: [0; 32] }, 8).err(), Some(Error::InvalidKey));
}

#[test]
fn registrations_round_trip_and_import_validates() {
    let k = Identity::generate().unwrap();
    let (mut host, hp, reg, _) = host_with(&k, Limits::default());
    let (mut a, _) = connect(&mut host, &k, &hp, reg, 1);
    ping(&mut host, &mut a, 2).unwrap();
    let blob = host.export_registrations();
    assert_eq!(blob.len(), 57);
    let mut restarted = HelperHost::new(Identity::generate().unwrap(), Limits::default());
    restarted.import_registrations(&blob, 3, true).unwrap();
    assert_eq!(restarted.registrations(), host.registrations());
    let mut bad = blob.clone();
    bad[16..48].copy_from_slice(&hex32(BAD_KEYS[2]));
    assert_eq!(restarted.import_registrations(&bad, 3, true).err(), Some(Error::InvalidKey));
    assert!(restarted.import_registrations(&blob[..56], 3, true).is_err());
}

/// Global half-open pressure from other registrations never evicts an
/// established session, even when that session is the least recently used.
#[test]
fn global_half_open_pressure_spares_established_sessions() {
    let k = Identity::generate().unwrap();
    let l = Limits::default();
    let (mut host, hp, reg_a, _) = host_with(&k, l);
    let (mut a, _) = connect(&mut host, &k, &hp, reg_a, 0);
    ping(&mut host, &mut a, 1).unwrap(); // established, last used at t=1
    let others: Vec<(Identity, [u8; 16])> = (0..3u8)
        .map(|i| {
            let id = Identity::generate().unwrap();
            let reg = [0x50 + i; 16];
            host.add_pending(&Pinned { registration_id: reg, peer_public: id.public }, 2).unwrap();
            (id, reg)
        })
        .collect();
    let mut t = 10;
    for (id, reg) in &others {
        for _ in 0..l.max_half_open_per_registration {
            let (_, _hs1) = connect(&mut host, id, &hp, *reg, t);
            t += 1;
        }
    }
    let st = host.stats();
    assert!(st.half_open <= l.max_half_open, "{st:?}");
    assert_eq!(st.established, 1);
    ping(&mut host, &mut a, t).unwrap();
}

/// Limits = 0 must neither hang the eviction loops nor disable sessions.
#[test]
fn zero_limits_are_clamped_and_cannot_hang() {
    let k = Identity::generate().unwrap();
    let zero = Limits {
        max_registrations: 0, pending_ttl_ms: 0, max_half_open: 0, max_half_open_per_registration: 0, handshake_ttl_ms: 0,
        max_established: 0, max_established_per_registration: 0, idle_ttl_ms: 0, max_session_ms: 0,
    };
    let s = zero.sanitized();
    assert!(s.max_registrations == 1 && s.max_half_open == 1 && s.max_established_per_registration == 1 && s.handshake_ttl_ms == 1);
    let (mut host, hp, reg, _) = host_with(&k, zero);
    let (mut a, _) = connect(&mut host, &k, &hp, reg, 0);
    ping(&mut host, &mut a, 1).unwrap();
    let (mut b, _) = connect(&mut host, &k, &hp, reg, 1);
    ping(&mut host, &mut b, 2).unwrap();
    assert_eq!(host.stats().established, 1);
}

/// R3: after a reboot the monotonic clock restarts; stored times must not
/// keep pending registrations alive or make confirmed ones look ancient.
#[test]
fn import_after_reboot_drops_pending_and_rebases_confirmed() {
    let k = Identity::generate().unwrap();
    let l = Limits::default();
    let (mut host, hp, reg, _) = host_with(&k, l);
    let (mut a, _) = connect(&mut host, &k, &hp, reg, 50_000);
    ping(&mut host, &mut a, 50_001).unwrap();
    host.add_pending(&Pinned { registration_id: [0x61; 16], peer_public: Identity::generate().unwrap().public }, 50_002).unwrap();
    let blob = host.export_registrations();
    // Same boot, clock moved on: pending keeps its time; future times are clamped.
    let mut same = HelperHost::new(Identity::generate().unwrap(), l);
    same.import_registrations(&blob, 40_000, true).unwrap();
    assert_eq!(same.registrations().len(), 2);
    assert!(same.registrations().iter().all(|r| r.created_ms <= 40_000));
    // Reboot: clock restarted at a small value.
    let mut rebooted = HelperHost::new(Identity::generate().unwrap(), l);
    rebooted.import_registrations(&blob, 1_000, false).unwrap();
    assert_eq!(rebooted.registrations().len(), 1);
    assert!(rebooted.registrations()[0].confirmed && rebooted.registrations()[0].created_ms == 1_000);
}

/// R1 (known availability risk, NOT fixed): replayed HS1 messages can evict a
/// legitimate half-open session before its first record arrives, so the
/// legitimate client must handshake again. No confidentiality impact.
#[test]
fn r1_hs1_replay_can_evict_a_fresh_legitimate_handshake() {
    let k = Identity::generate().unwrap();
    let (mut host, hp, reg, _) = host_with(&k, Limits::default());
    let (_, recorded_hs1) = connect(&mut host, &k, &hp, reg, 0); // attacker records this HS1
    let (mut legit, _) = connect(&mut host, &k, &hp, reg, 100); // fresh legitimate handshake
    for t in 0..Limits::default().max_half_open_per_registration as u64 {
        host.handshake(&recorded_hs1, 101 + t).unwrap();
    }
    assert_eq!(ping(&mut host, &mut legit, 200).err(), Some(Error::UnknownSession), "legitimate half-open evicted");
    let (mut again, _) = connect(&mut host, &k, &hp, reg, 300); // retry succeeds once the flood stops
    ping(&mut host, &mut again, 301).unwrap();
}

/// R2 (known availability risk, NOT fixed): anyone who knows a session id
/// can end that session with one garbage record. Authentication is not
/// weakened to keep it alive; the client re-handshakes.
#[test]
fn r2_known_session_id_allows_termination_but_not_takeover() {
    let k = Identity::generate().unwrap();
    let (mut host, hp, reg, _) = host_with(&k, Limits::default());
    let (mut a, _) = connect(&mut host, &k, &hp, reg, 0);
    ping(&mut host, &mut a, 1).unwrap();
    let mut garbage = b"KHC2\x20".to_vec();
    garbage.extend_from_slice(&a.session_id());
    garbage.extend_from_slice(&[0u8; 40]);
    assert_eq!(host.open(&garbage, 2).err(), Some(Error::AuthFailed));
    assert_eq!(ping(&mut host, &mut a, 3).err(), Some(Error::UnknownSession));
    let (mut b, _) = connect(&mut host, &k, &hp, reg, 4);
    ping(&mut host, &mut b, 5).unwrap();
}

/// R4: Kadri rejected the new pin after the helper already confirmed it
/// (e.g. the confirmation reply was lost). Kadri keeps using the old
/// registration, which must keep working; the unused new one is retired.
#[test]
fn r4_rejected_new_pin_keeps_the_previous_registration() {
    let k = Identity::generate().unwrap();
    let (mut host, hp, reg1, _) = host_with(&k, Limits::default());
    let (mut a, _) = connect(&mut host, &k, &hp, reg1, 1);
    ping(&mut host, &mut a, 2).unwrap();
    let reg2 = [0x42; 16];
    host.add_pending(&Pinned { registration_id: reg2, peer_public: k.public }, 3).unwrap();
    let (mut b, _) = connect(&mut host, &k, &hp, reg2, 4);
    let rec = b.seal_app(&AppRecord::Control(b"{}".to_vec())).unwrap();
    host.open(&rec, 5).unwrap(); // helper-side confirmation; Kadri never sees the reply
    let (mut again, _) = connect(&mut host, &k, &hp, reg1, 6);
    ping(&mut host, &mut again, 7).unwrap();
    assert_eq!(host.registrations().len(), 1);
    assert_eq!(host.registrations()[0].id, reg1);
}
