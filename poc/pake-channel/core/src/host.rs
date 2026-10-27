//! Helper-side registrations and session table with fixed resource limits.
//!
//! Noise KK message 1 is replayable by design: the responder cannot tell a
//! recorded HS1 from a fresh one. A replayed HS1 therefore yields a
//! *half-open* session that only the genuine initiator can promote, by
//! sending a record that authenticates. Policy:
//!
//! - half-open sessions live in their own bounded pool and expire quickly;
//!   pressure on that pool only ever evicts other half-open sessions;
//! - established sessions are evicted only to make room for a *newly
//!   established* session (i.e. after the initiator proved its key), only
//!   within the same registration first, and never by unauthenticated input;
//! - a registration stays "pending" until its first established session and
//!   is dropped if that never happens (lost M3, abandoned pairing).

use crate::channel::{self, Channel};
use crate::pairing::Pinned;
use crate::record::AppRecord;
use crate::{validate_public_key, Error, Identity, ID_LEN, KEY_LEN};
use std::collections::HashMap;

#[derive(Debug, Clone, Copy)]
pub struct Limits {
    pub max_registrations: usize,
    pub pending_ttl_ms: u64,
    pub max_half_open: usize,
    pub max_half_open_per_registration: usize,
    pub handshake_ttl_ms: u64,
    pub max_established: usize,
    pub max_established_per_registration: usize,
    pub idle_ttl_ms: u64,
    pub max_session_ms: u64,
}

impl Limits {
    /// Every count at least 1 and every duration at least 1 ms: a zero limit
    /// would otherwise make the eviction loops below spin forever or make
    /// every session expire instantly.
    pub fn sanitized(self) -> Self {
        Self {
            max_registrations: self.max_registrations.max(1),
            pending_ttl_ms: self.pending_ttl_ms.max(1),
            max_half_open: self.max_half_open.max(1),
            max_half_open_per_registration: self.max_half_open_per_registration.max(1),
            handshake_ttl_ms: self.handshake_ttl_ms.max(1),
            max_established: self.max_established.max(1),
            max_established_per_registration: self.max_established_per_registration.max(1),
            idle_ttl_ms: self.idle_ttl_ms.max(1),
            max_session_ms: self.max_session_ms.max(1),
        }
    }
}

impl Default for Limits {
    fn default() -> Self {
        Self {
            max_registrations: 4,
            pending_ttl_ms: 10 * 60_000,
            max_half_open: 4,
            max_half_open_per_registration: 2,
            handshake_ttl_ms: 10_000,
            max_established: 4,
            max_established_per_registration: 2,
            idle_ttl_ms: 120_000,
            max_session_ms: 4 * 3_600_000,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Registration {
    pub id: [u8; ID_LEN],
    pub kadri_public: [u8; KEY_LEN],
    pub confirmed: bool,
    pub created_ms: u64,
}

struct Session {
    registration: [u8; ID_LEN],
    channel: Channel,
    established: bool,
    created_ms: u64,
    last_ms: u64,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Stats {
    pub registrations: usize,
    pub half_open: usize,
    pub established: usize,
}

pub struct HelperHost {
    identity: Identity,
    limits: Limits,
    registrations: Vec<Registration>,
    sessions: HashMap<[u8; ID_LEN], Session>,
}

impl HelperHost {
    pub fn new(identity: Identity, limits: Limits) -> Self {
        Self { identity, limits: limits.sanitized(), registrations: Vec::new(), sessions: HashMap::new() }
    }

    pub fn public(&self) -> [u8; KEY_LEN] {
        self.identity.public
    }

    pub fn stats(&self) -> Stats {
        let est = self.sessions.values().filter(|s| s.established).count();
        Stats { registrations: self.registrations.len(), half_open: self.sessions.len() - est, established: est }
    }

    pub fn registrations(&self) -> &[Registration] {
        &self.registrations
    }

    /// Drops expired pending registrations and timed-out sessions.
    pub fn sweep(&mut self, now: u64) {
        let l = self.limits;
        self.registrations.retain(|r| r.confirmed || now <= r.created_ms.saturating_add(l.pending_ttl_ms));
        let regs: Vec<[u8; ID_LEN]> = self.registrations.iter().map(|r| r.id).collect();
        self.sessions.retain(|_, s| {
            regs.contains(&s.registration)
                && if s.established {
                    now <= s.last_ms.saturating_add(l.idle_ttl_ms) && now <= s.created_ms.saturating_add(l.max_session_ms)
                } else {
                    now <= s.created_ms.saturating_add(l.handshake_ttl_ms)
                }
        });
    }

    /// Checked before M3 is released, so a full registry fails the pairing.
    pub fn can_register(&mut self, now: u64) -> bool {
        self.sweep(now);
        self.registrations.len() < self.limits.max_registrations
    }

    pub fn add_pending(&mut self, pinned: &Pinned, now: u64) -> Result<(), Error> {
        validate_public_key(&pinned.peer_public)?;
        if !self.can_register(now) {
            return Err(Error::RegistryFull);
        }
        if self.registrations.iter().any(|r| r.id == pinned.registration_id) {
            return Err(Error::Malformed);
        }
        self.registrations.push(Registration { id: pinned.registration_id, kadri_public: pinned.peer_public, confirmed: false, created_ms: now });
        Ok(())
    }

    pub fn revoke(&mut self, id: &[u8; ID_LEN]) {
        self.registrations.retain(|r| &r.id != id);
        self.sessions.retain(|_, s| &s.registration != id);
    }

    /// Terminal close of one session (e.g. an unexpected record kind).
    pub fn close(&mut self, sid: &[u8; ID_LEN]) {
        self.sessions.remove(sid);
    }

    pub fn revoke_all(&mut self) {
        self.registrations.clear();
        self.sessions.clear();
    }

    /// Removes the least recently used matching session; false if none matched.
    fn evict_oldest(&mut self, pred: impl Fn(&Session) -> bool) -> bool {
        match self.sessions.iter().filter(|(_, s)| pred(s)).min_by_key(|(_, s)| s.last_ms).map(|(k, _)| *k) {
            Some(k) => self.sessions.remove(&k).is_some(),
            None => false,
        }
    }

    /// HS1 -> HS2. Creates a half-open session.
    pub fn handshake(&mut self, hs1: &[u8], now: u64) -> Result<Vec<u8>, Error> {
        self.sweep(now);
        let (reg_id, _) = channel::split(hs1, 0x10)?;
        let reg = self.registrations.iter().find(|r| r.id == reg_id).map(|r| r.kadri_public);
        let (ch, hs2) = channel::respond(&self.identity, hs1, |_| reg)?;
        let l = self.limits;
        while self.sessions.values().filter(|s| !s.established && s.registration == reg_id).count() >= l.max_half_open_per_registration {
            if !self.evict_oldest(|s| !s.established && s.registration == reg_id) {
                break;
            }
        }
        while self.sessions.values().filter(|s| !s.established).count() >= l.max_half_open {
            if !self.evict_oldest(|s| !s.established) {
                break;
            }
        }
        self.sessions.insert(ch.session_id(), Session { registration: reg_id, channel: ch, established: false, created_ms: now, last_ms: now });
        Ok(hs2)
    }

    /// Opens a record. Any failure removes the session (terminal).
    pub fn open(&mut self, record: &[u8], now: u64) -> Result<([u8; ID_LEN], AppRecord), Error> {
        self.sweep(now);
        let (sid, _) = channel::split(record, 0x20)?;
        let s = self.sessions.get_mut(&sid).ok_or(Error::UnknownSession)?;
        let rec = match s.channel.open_app(record) {
            Ok(r) => r,
            Err(e) => {
                self.sessions.remove(&sid);
                return Err(e);
            }
        };
        s.last_ms = now;
        if !s.established {
            self.promote(sid);
        }
        Ok((sid, rec))
    }

    /// The initiator has proven its key: make room among established
    /// sessions (same registration first), confirm the registration and
    /// retire older registrations of the same Kadri key.
    fn promote(&mut self, sid: [u8; ID_LEN]) {
        let reg_id = self.sessions[&sid].registration;
        let l = self.limits;
        while self.sessions.values().filter(|s| s.established && s.registration == reg_id).count() >= l.max_established_per_registration {
            if !self.evict_oldest(|s| s.established && s.registration == reg_id) {
                break;
            }
        }
        while self.sessions.values().filter(|s| s.established).count() >= l.max_established {
            if !self.evict_oldest(|s| s.established) {
                break;
            }
        }
        self.sessions.get_mut(&sid).unwrap().established = true;
        let key = match self.registrations.iter_mut().find(|r| r.id == reg_id) {
            Some(r) => {
                r.confirmed = true;
                r.kadri_public
            }
            None => return,
        };
        let stale: Vec<[u8; ID_LEN]> = self.registrations.iter().filter(|r| r.id != reg_id && r.kadri_public == key).map(|r| r.id).collect();
        for id in stale {
            self.revoke(&id);
        }
    }

    pub fn seal(&mut self, sid: &[u8; ID_LEN], rec: &AppRecord, now: u64) -> Result<Vec<u8>, Error> {
        let s = self.sessions.get_mut(sid).ok_or(Error::UnknownSession)?;
        match s.channel.seal_app(rec) {
            Ok(out) => {
                s.last_ms = now;
                Ok(out)
            }
            Err(e) => {
                self.sessions.remove(sid);
                Err(e)
            }
        }
    }

    /// Registrations as fixed 57-byte rows: id(16) key(32) confirmed(1) created(8, BE).
    pub fn export_registrations(&self) -> Vec<u8> {
        let mut out = Vec::with_capacity(self.registrations.len() * 57);
        for r in &self.registrations {
            out.extend_from_slice(&r.id);
            out.extend_from_slice(&r.kadri_public);
            out.push(r.confirmed as u8);
            out.extend_from_slice(&r.created_ms.to_be_bytes());
        }
        out
    }

    /// Loads persisted registrations. `now` is on the caller's monotonic
    /// clock (Android: elapsedRealtime). After a reboot (`same_boot == false`)
    /// that clock restarted, so stored times are meaningless: pending
    /// registrations are dropped and confirmed ones restart their clock.
    /// Within the same boot, times from the future are clamped to `now`.
    pub fn import_registrations(&mut self, b: &[u8], now: u64, same_boot: bool) -> Result<(), Error> {
        if b.len() % 57 != 0 || b.len() / 57 > self.limits.max_registrations {
            return Err(Error::Malformed);
        }
        let mut regs = Vec::new();
        for row in b.chunks(57) {
            let mut id = [0u8; ID_LEN];
            let mut key = [0u8; KEY_LEN];
            id.copy_from_slice(&row[..16]);
            key.copy_from_slice(&row[16..48]);
            validate_public_key(&key)?;
            if row[48] > 1 {
                return Err(Error::Malformed);
            }
            regs.push(Registration { id, kadri_public: key, confirmed: row[48] == 1, created_ms: u64::from_be_bytes(row[49..57].try_into().unwrap()) });
        }
        if !same_boot {
            regs.retain(|r| r.confirmed);
        }
        for r in regs.iter_mut() {
            if !same_boot || r.created_ms > now {
                r.created_ms = now;
            }
        }
        self.registrations = regs;
        self.sessions.clear();
        Ok(())
    }
}
