#!/usr/bin/env python3
"""Mutationsproben der sicheren Netzwerkschicht: jede Probe schwächt genau eine Schutzmaßnahme in einer Kopie ab;
die Testsuite (tests/java/net/run.sh) muss danach fehlschlagen. Aufruf: mutation.py [CACHE_DIR]"""
import os
import shutil
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, '..', '..', '..'))
NET = 'app/src/main/java/de/evp/helfer/net/'

MUTATIONS = [
    ('socket without NO_PROXY', NET + 'GuardedSocketFactory.java', 'super(Proxy.NO_PROXY);', 'super();'),
    ('socket check removed', NET + 'GuardedSocketFactory.java', 'rules.checkEndpoint(target.getAddress(), target.getPort(), false);', ''),
    ('client proxy not disabled', NET + 'SafeHttpClient.java', '.proxy(Proxy.NO_PROXY)', ''),
    ('automatic redirects', NET + 'SafeHttpClient.java', '.followRedirects(false)', '.followRedirects(true)'),
    ('downgrade check removed', NET + 'SafeHttpClient.java',
     'if (current.isHttps() && !next.isHttps()) throw new NetBlockedException(NetBlockedException.Reason.DOWNGRADE, next.host());', ''),
    ('redirect target unchecked', NET + 'SafeHttpClient.java', 'rules.checkUrl(next);', ''),
    ('retry on connection failure', NET + 'SafeHttpClient.java', '.retryOnConnectionFailure(false)', '.retryOnConnectionFailure(true)'),
    ('Retry-After kept', NET + 'SafeHttpClient.java', 'response.code() == 503 && response.header("Retry-After") != null', 'false'),
    ('peer check removed', NET + 'SafeHttpClient.java', 'rules.checkEndpoint(s.getInetAddress(), s.getPort(), true);', ''),
    ('DNS answers unchecked', NET + 'SafeHttpClient.java', 'rules.checkResolved(host, addresses);', ''),
    ('HTTP/2 allowed', NET + 'SafeHttpClient.java', '.protocols(Collections.singletonList(Protocol.HTTP_1_1))', ''),
    ('transparent gzip', NET + 'SafeHttpClient.java', '.header("Accept-Encoding", "identity")', ''),
    ('header limit removed', NET + 'SafeHttpClient.java', 'response.headers().byteCount() > MAX_RESPONSE_HEADER_BYTES', 'false'),
    ('CGNAT allowed', NET + 'AddressPolicy.java', '{0x64400000, 10},', ''),
    ('link-local v4 allowed', NET + 'AddressPolicy.java', '{0xA9FE0000, 16},', ''),
    ('IPv4-mapped not decoded', NET + 'AddressPolicy.java',
     'if (allZero(b, 0, 10) && (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff) return allowedV4(b);', ''),
    ('NAT64 not decoded', NET + 'AddressPolicy.java', 'if (Arrays.equals(Arrays.copyOf(b, 12), NAT64_WELL_KNOWN)) return allowedV4(b);',
     'if (Arrays.equals(Arrays.copyOf(b, 12), NAT64_WELL_KNOWN)) return true;'),
    ('6to4 allowed', NET + 'AddressPolicy.java', 'if (w0 == 0x2002) return false;', ''),
    ('numeric hosts allowed', NET + 'NetRules.java', '} else if (endsInNumber(host)) {', '} else if (false) {'),
    ('credentials in URL allowed', NET + 'NetRules.java', 'if (!url.username().isEmpty() || !url.password().isEmpty())', 'if (false)'),
    ('*.localhost allowed', NET + 'NetRules.java', 'h.endsWith(".localhost") ||', ''),
    ('single-label names allowed', NET + 'NetRules.java', '|| !h.contains(".")', ''),
    ('body limit removed', NET + 'SafeResponse.java', 'if (count > max) throw', 'if (false) throw'),
    ('NAT64 discovery: unknown treated as none', NET + 'Nat64Discovery.java', 'if (answers == null) return ALL_IPV6;', 'if (answers == null) return Collections.emptyList();'),
    ('NAT64 discovery: IPv6-only without DNS64 allowed', NET + 'Nat64Discovery.java',
     'return networkHasIpv4 ? Collections.emptyList() : ALL_IPV6;', 'return Collections.emptyList();'),
    ('NAT64 discovery: unexplained AAAA ignored', NET + 'Nat64Discovery.java', 'if (p == null) return ALL_IPV6;', 'if (p == null) continue;'),
    ('NAT64 discovery: u octet ignored', NET + 'Nat64Discovery.java', 'if (bits < 96 && b[8] != 0) continue;', ''),
]


def main():
    cache = sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.environ.get('RUNNER_TEMP', '/tmp'), 'safe-http-deps')
    survivors = []
    for name, rel, old, new in MUTATIONS:
        work = tempfile.mkdtemp(prefix='safehttp-mut-')
        try:
            for d in ('app/src/main/java/de/evp/helfer/net', 'tests/java/net'):
                shutil.copytree(os.path.join(ROOT, d), os.path.join(work, d))
            path = os.path.join(work, rel)
            src = open(path, encoding='utf-8').read()
            if src.count(old) != 1:
                print('ERROR mutation does not apply exactly once:', name)
                survivors.append(name)
                continue
            open(path, 'w', encoding='utf-8').write(src.replace(old, new))
            r = subprocess.run(['bash', os.path.join(work, 'tests/java/net/run.sh'), cache], capture_output=True, text=True, timeout=900)
            fails = [l for l in r.stdout.splitlines() if l.startswith('FAIL ')]
            if r.returncode == 0:
                print('SURVIVED', name)
                survivors.append(name)
            else:
                print('KILLED  ', name, '|', fails[0][5:90] if fails else 'build/run failure')
        finally:
            shutil.rmtree(work, ignore_errors=True)
    print('RESULT safe-http-mutations', 'PASS' if not survivors else 'FAIL', len(MUTATIONS) - len(survivors), '/', len(MUTATIONS))
    sys.exit(0 if not survivors else 1)


if __name__ == '__main__':
    main()
