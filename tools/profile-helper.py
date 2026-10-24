#!/usr/bin/env python3
"""Emulator helper startup/idle/RPC observations; no battery or video conclusions."""
import argparse, json, os, re, subprocess, time, urllib.request, xml.etree.ElementTree as ET
p=argparse.ArgumentParser();p.add_argument('--apk',required=True);p.add_argument('--label',required=True);p.add_argument('--output',required=True);a=p.parse_args()
pkg='de.evp.helfer.debug'
def adb(*args,check=True):
    return subprocess.run(['adb',*args],check=check,text=True,capture_output=True).stdout
def cpu_ticks():
    pid=adb('shell','pidof',pkg).strip().split()[0]
    stat=adb('shell','run-as',pkg,'cat','/proc/'+pid+'/stat')
    fields=stat[stat.rfind(')')+2:].split()
    return int(fields[11])+int(fields[12])
def memory():
    text=adb('shell','dumpsys','meminfo',pkg)
    m=re.search(r'TOTAL PSS:\s*(\d+)',text)
    if not m: raise RuntimeError('TOTAL PSS unavailable; do not infer zero memory')
    return int(m.group(1))
def request(key):
    req=urllib.request.Request('http://127.0.0.1:47812/ping',headers={'X-EVP-Key':key})
    with urllib.request.urlopen(req,timeout=2) as r:
        if r.status!=200: raise RuntimeError('Helper response not successful')
        return r.read()
out={'label':a.label,'android_api':adb('shell','getprop','ro.build.version.sdk').strip(),
     'battery':'not measured','scope':'foreground helper idle and /ping only, emulator'}
try:
    adb('uninstall',pkg,check=False);adb('install','--no-incremental',a.apk)
    adb('forward','tcp:47812','tcp:47811')
    begin=time.perf_counter()
    out['am_start_output']=adb('shell','am','start','-W','-n',pkg+'/de.evp.helfer.MainActivity')
    deadline=time.monotonic()+20
    key=None
    while time.monotonic()<deadline:
        try:
            # Trigger key generation if this version generates the key on its first request.
            try: request('test-invalid-key')
            except Exception: pass
            text=adb('shell','run-as',pkg,'cat','shared_prefs/helfer.xml')
            root=ET.fromstring(text)
            key=next(x.text for x in root if x.attrib.get('name')=='key')
            request(key);break
        except Exception: time.sleep(.1)
    else: raise RuntimeError('Helper never became authenticated-ready')
    out['authenticated_ready_ms']=(time.perf_counter()-begin)*1000
    out['pss_before_kib']=memory()
    start=time.perf_counter();ticks=cpu_ticks();time.sleep(10)
    out['idle_cpu_ticks']=cpu_ticks()-ticks;out['idle_window_s']=time.perf_counter()-start
    ticks=cpu_ticks();start=time.perf_counter()
    for _ in range(100): request(key)
    out['rpc_100_ms']=(time.perf_counter()-start)*1000
    out['rpc_cpu_ticks']=cpu_ticks()-ticks
    out['pss_after_kib']=memory()
    # No pairing key or preference XML is written to results.
    with open(a.output,'a') as f:f.write(json.dumps(out)+'\n')
    print(json.dumps(out))
finally:
    adb('forward','--remove','tcp:47812',check=False);adb('uninstall',pkg,check=False)
