#!/usr/bin/env python3
"""Targeted mutation probe for the PoC core.

Each mutant removes or weakens one check. A killed mutant means some test
fails. Survivors are listed with the reason they are acceptable, if any;
an unexplained survivor fails the run. Sources are always restored.
Usage: tools/mutation_check.py [name-filter]
"""
import functools, pathlib, subprocess, sys
print = functools.partial(print, flush=True)

CORE = pathlib.Path(__file__).resolve().parent.parent / 'core'

# (name, file, old, new, expected_survivor_reason or None)
MUTANTS = [
    ('pairing: identities without keys', 'src/pairing.rs', '[ID_PREFIX_KADRI, &public[..]].concat()', 'ID_PREFIX_KADRI.to_vec()', None),
    ('pairing: helper skips cA', 'src/pairing.rs', 'output.verify_peer_confirmation(ca).map_err(|_| Error::ConfirmationFailed)?;', 'let _ = ca;', None),
    ('pairing: kadri skips cB', 'src/pairing.rs', 'output.verify_peer_confirmation(&b[ID_LEN..]).map_err(|_| Error::ConfirmationFailed)?;', '', None),
    ('pairing: window not burned on M2 failure', 'src/pairing.rs', 'let state = core::mem::replace(&mut self.state, HelperState::Burned);', 'let state = core::mem::replace(&mut self.state, HelperState::Open);', None),
    ('pairing: no helper deadline at M0', 'src/pairing.rs', 'if now_ms > self.deadline_ms {\n            return Err(self.burn(Error::Expired));\n        }\n        let b = match parse(m0', 'let b = match parse(m0', None),
    ('pairing: helper deadline off by one', 'src/pairing.rs', 'if now_ms > self.deadline_ms {\n            return Err(self.burn(Error::Expired));\n        }\n        let b = match parse(m0', 'if now_ms >= self.deadline_ms {\n            return Err(self.burn(Error::Expired));\n        }\n        let b = match parse(m0', None),
    ('pairing: no helper deadline at M2', 'src/pairing.rs', 'if now_ms > self.deadline_ms {\n            return Err(Error::Expired);\n        }\n        let b = parse(m2', 'let b = parse(m2', None),
    ('pairing: no kadri deadline at M1', 'src/pairing.rs', 'if now_ms > self.deadline_ms {\n            return Err(self.fail(Error::Expired));\n        }', '', None),
    ('pairing: no kadri deadline at M3', 'src/pairing.rs', 'if now_ms > self.deadline_ms {\n            return Err(Error::Expired);\n        }\n        let b = parse(m3', 'let b = parse(m3', None),
    ('pairing: pairing id not in AAD', 'src/lib.rs', 'out[26..].copy_from_slice(pairing_id);', '', None),
    ('pairing: helper ignores M2 pairing id', 'src/pairing.rs', 'if take::<ID_LEN>(b, 0) != pairing_id {\n            return Err(Error::Malformed);\n        }\n        let pa', 'let pa', 'equivalent: with the id in the AAD a foreign id makes cA fail (tests then see ConfirmationFailed instead of Malformed)'),
    ('pairing: kadri ignores M3 pairing id', 'src/pairing.rs', 'if take::<ID_LEN>(b, 0) != pairing_id {\n            return Err(Error::Malformed);\n        }\n        output', 'output', None),
    ('pairing: helper skips Kadri key validation', 'src/pairing.rs', 'if validate_public_key(&kadri_public).is_err() || kadri_public == self.helper_public {', 'if kadri_public == self.helper_public {', None),
    ('pairing: kadri accepts its own key as helper key', 'src/pairing.rs', 'if validate_public_key(&helper_public).is_err() || helper_public == self.kadri_public {', 'if validate_public_key(&helper_public).is_err() {', None),
    ('pairing: second M0 burns instead of refusing', 'src/pairing.rs', 'HelperState::AwaitingConfirm { .. } => return Err(Error::WrongState),', 'HelperState::AwaitingConfirm { .. } => return Err(self.burn(Error::WrongState)),', None),
    ('code: rejection bound off by one', 'src/pairing.rs', 'if v >= CODE_REJECT_FROM {', 'if v > CODE_REJECT_FROM {', None),
    ('code: digits not enforced', 'src/pairing.rs', '|| !b.iter().all(u8::is_ascii_digit)', '', None),
    ('argon2: t=1', 'src/pairing.rs', 'const T_COST: u32 = 2;', 'const T_COST: u32 = 1;', None),
    ('channel: wrong session id not terminal', 'src/channel.rs', 'if sid != self.session_id {\n            return self.kill(Error::Malformed);', 'if sid != self.session_id {\n            return Err(Error::Malformed);', None),
    ('channel: bad header not terminal', 'src/channel.rs', 'Ok(v) => v,\n            Err(e) => return self.kill(e),', 'Ok(v) => v,\n            Err(e) => return Err(e),', None),
    ('channel: bad app record not terminal', 'src/channel.rs', 'Ok(r) => Ok(r),\n            Err(e) => self.kill(e),', 'Ok(r) => Ok(r),\n            Err(e) => Err(e),', None),
    ('channel: auth failure not terminal', 'src/channel.rs', '_ => self.kill(Error::AuthFailed),', '_ => Err(Error::AuthFailed),', None),
    ('channel: oversized record check removed', 'src/channel.rs', 'if record.len() > MAX_RECORD {\n            return self.kill(Error::TooLarge);\n        }', '', 'equivalent for safety: snow rejects >65535 B messages and the channel dies with AuthFailed; only the error name differs'),
    ('channel: responder accepts handshake payload', 'src/channel.rs', 'if n != 0 {\n        return Err(Error::AuthFailed);\n    }', '', 'redundant guard: HS1 has a fixed length that leaves no room for payload; the outer length check rejects it'),
    ('channel: registration id not in prologue', 'src/lib.rs', 'out[26..].copy_from_slice(registration_id);', '', None),
    ('channel: initiator single-use not enforced', 'src/channel.rs', 'let mut hs = self.hs.take().ok_or(Error::WrongState)?;\n        if hs2.len() != HS_LEN {', 'let mut hs = self.hs.take().ok_or(Error::WrongState)?;\n        self.hs = None;\n        if hs2.len() != HS_LEN {', 'not a real mutant of the property (take() already consumes); kept as a sanity control'),
    ('keys: small-order points accepted', 'src/lib.rs', 'if ed.is_small_order() || ed.to_montgomery().to_bytes() != *key {', 'if ed.to_montgomery().to_bytes() != *key {', None),
    ('keys: non-canonical encodings accepted', 'src/lib.rs', 'if ed.is_small_order() || ed.to_montgomery().to_bytes() != *key {', 'if ed.is_small_order() {', None),
    ('host: half-open eviction may hit established', 'src/host.rs', 'while self.sessions.values().filter(|s| !s.established).count() >= l.max_half_open {\n            self.evict_oldest(|s| !s.established);', 'while self.sessions.values().filter(|s| !s.established).count() >= l.max_half_open {\n            self.evict_oldest(|_| true);', 'unreachable with defaults: the per-registration half-open cap (2) is below the global cap (4) for a single registration; covered by the per-registration mutant below'),
    ('host: per-registration half-open eviction may hit established', 'src/host.rs', 'self.evict_oldest(|s| !s.established && s.registration == reg_id);', 'self.evict_oldest(|s| s.registration == reg_id);', None),
    ('host: half-open sessions never expire', 'src/host.rs', 'now <= s.created_ms.saturating_add(l.handshake_ttl_ms)', 'true', None),
    ('host: no idle timeout', 'src/host.rs', 'now <= s.last_ms.saturating_add(l.idle_ttl_ms) && ', '', None),
    ('host: no session age limit', 'src/host.rs', ' && now <= s.created_ms.saturating_add(l.max_session_ms)', '', None),
    ('host: pending registrations never expire', 'src/host.rs', 'r.confirmed || now <= r.created_ms.saturating_add(l.pending_ttl_ms)', 'true', None),
    ('host: revoke keeps sessions', 'src/host.rs', 'self.registrations.retain(|r| &r.id != id);\n        self.sessions.retain(|_, s| &s.registration != id);', 'self.registrations.retain(|r| &r.id != id);', 'sweep() drops sessions of removed registrations on the next call, before any record is processed'),
    ('host: failing record keeps session', 'src/host.rs', 'Err(e) => {\n                self.sessions.remove(&sid);\n                return Err(e);', 'Err(e) => {\n                return Err(e);', 'the Channel itself is dead after the error (WrongState); only the slot is held until timeout'),
    ('host: registry capacity ignored', 'src/host.rs', 'self.registrations.len() < self.limits.max_registrations', 'true', None),
    ('record: media length not exact', 'src/record.rs', 'if n == 0 || n > MAX_MEDIA || b.len() != 15 + n {', 'if n == 0 || n > MAX_MEDIA {', None),
    ('record: media size bound removed on encode', 'src/record.rs', 'if data.len() > MAX_MEDIA || data.is_empty() {', 'if data.is_empty() {', None),
]


def run_tests():
    r = subprocess.run(['cargo', 'test', '--locked', '-q'], cwd=CORE, capture_output=True, text=True)
    return r.returncode == 0


def main():
    flt = sys.argv[1] if len(sys.argv) > 1 else ''
    unexplained = 0
    for name, rel, old, new, reason in MUTANTS:
        if flt not in name:
            continue
        path = CORE / rel
        src = path.read_text()
        if src.count(old) != 1:
            print(f'BROKEN   {name}: pattern found {src.count(old)} times'); unexplained += 1; continue
        try:
            path.write_text(src.replace(old, new, 1))
            survived = run_tests()
        finally:
            path.write_text(src)
        if not survived:
            print(f'killed   {name}')
        elif reason:
            print(f'survived {name} — accepted: {reason}')
        else:
            print(f'SURVIVED {name} — UNEXPLAINED'); unexplained += 1
    print('MUTATION RESULT', 'FAIL' if unexplained else 'PASS')
    return 1 if unexplained else 0


if __name__ == '__main__':
    sys.exit(main())
