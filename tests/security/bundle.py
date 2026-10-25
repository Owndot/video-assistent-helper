"""Reproducible integrity/size tests and browser startup comparison; no external resources."""
import hashlib, json, pathlib, statistics, subprocess, tempfile
from playwright.sync_api import sync_playwright
ROOT=pathlib.Path(__file__).resolve().parents[2]
lock=json.loads((ROOT/'vendor/hls.lock.json').read_text())
vendor=(ROOT/'vendor/hls.min.js').read_bytes()
script=(ROOT/'userscript/video-assistent.user.js').read_text()
assert hashlib.sha256(vendor).hexdigest()==lock['sha256']
assert '// @require ' not in script
assert 'eval(' not in (ROOT/'tools/build.js').read_text()
subprocess.run(['node',str(ROOT/'tools/build.js'),'--check'],check=True)
# ParseScript installs the same namespace/name; explicit version bump enables manager update detection.
assert '// @version      19.29' in script
# Former execution downloaded both the userscript and the same HLS library.
old=pathlib.Path('/tmp/previous-userscript.js').read_text()
baseline_bytes=len(old.encode())+len(vendor)
new_bytes=len(script.encode())
assert new_bytes <= baseline_bytes * 1.01,(new_bytes,baseline_bytes)
# A changed vendor must stop the build before dependency code is loaded.
with tempfile.TemporaryDirectory(prefix='hls-integrity-') as temp:
    temp=pathlib.Path(temp)
    import shutil
    for dirname in ['src','tools','vendor']:
        shutil.copytree(ROOT/dirname,temp/dirname)
    (temp/'userscript').mkdir()
    with (temp/'vendor/hls.min.js').open('ab') as f:f.write(b'\n/* synthetic tamper */')
    result=subprocess.run(['node',str(temp/'tools/build.js')],capture_output=True,text=True)
    assert result.returncode and 'differ from the reviewed lock' in result.stderr,result.stderr
# Interleave fresh pages to reduce ordering bias; include parser and initialization time.
measurements={'previous':[],'bundled':[]}
with sync_playwright() as p:
    browser=p.chromium.launch(headless=True)
    context=browser.new_context()
    for trial in range(16):
        for name,body in [('previous',vendor.decode()+'\n'+old),('bundled',script)][::1 if trial%2 else -1]:
            page=context.new_page();page.goto('about:blank')
            elapsed=page.evaluate('''body=>{const t=performance.now();new Function(body)();return performance.now()-t;}''',body)
            if trial:measurements[name].append(elapsed)
            page.close()
    browser.close()
previous=statistics.median(measurements['previous']);bundled=statistics.median(measurements['bundled'])
# A broad absolute allowance avoids flakes on shared runners; raw samples remain reviewable.
assert bundled <= previous*1.5+5,(previous,bundled)
report={'hlsVersion':lock['version'],'sha256':lock['sha256'],'previousUserscriptBytes':len(old.encode()),
 'previousTotalJavaScriptBytes':baseline_bytes,'bundledUserscriptBytes':new_bytes,
 'medianStartupMs':{'previous':previous,'bundled':bundled},'samplesMs':measurements}
out=ROOT/'validation-results';out.mkdir(exist_ok=True)
(out/'hls-bundle.json').write_text(json.dumps(report,indent=2))
print('PASS: pinned bytes, tamper rejection, manager update metadata, total payload budget, startup comparison')
print(json.dumps(report,indent=2))
