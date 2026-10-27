#!/usr/bin/env bash
# Proves the negative tests are load-bearing: each mutation removes one
# security check and MUST make tests/protocol.rs fail. Restores the source.
set -uo pipefail
cd "$(dirname "$0")/../core"
backup="$(mktemp)"; cp src/pairing.rs "$backup"; trap 'cp "$backup" src/pairing.rs' EXIT
mutate() { # name, python replacement
  cp "$backup" src/pairing.rs
  python3 -I -c "import sys;p='src/pairing.rs';s=open(p).read();o,n=sys.argv[1],sys.argv[2];assert o in s;open(p,'w').write(s.replace(o,n,1))" "$2" "$3"
  if cargo test -q --test protocol >/dev/null 2>&1; then echo "MUTANT SURVIVED: $1"; status=1; else echo "mutant killed: $1"; fi
}
status=0
mutate "identities not bound" '[ID_PREFIX_KADRI, &public[..]].concat()' 'ID_PREFIX_KADRI.to_vec()'
mutate "helper skips cA check" 'output.verify_peer_confirmation(ca).map_err(|_| Error::ConfirmationFailed)?;' 'let _ = ca;'
mutate "kadri skips cB check" 'output.verify_peer_confirmation(&b[ID_LEN..]).map_err(|_| Error::ConfirmationFailed)?;' ''
mutate "helper window not burned" 'let state = core::mem::replace(&mut self.state, HelperState::Burned);' 'let state = core::mem::replace(&mut self.state, HelperState::Open);'
mutate "no deadline on helper" 'if now_ms > self.deadline_ms {
            return Err(self.burn(Error::Expired));
        }
        let b = match parse(m0' 'let b = match parse(m0'
exit $status
