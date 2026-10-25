"""Real servers and browser/manager; no production endpoints or credentials.
Run with --userscript (VM_DIR required) or --kadri (EXT_DIR required).
"""
import argparse, functools, json, os, pathlib, ssl, subprocess, tempfile, threading, time
import http.server as http_server
from urllib.parse import urlsplit, parse_qs, quote
from playwright.sync_api import sync_playwright

ROOT = pathlib.Path(__file__).resolve().parents[2]
MEDIA = pathlib.Path(os.environ.get('FIXTURE_MEDIA', '/tmp/privacy-media'))
HITS = []
class Handler(http_server.SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs): super().__init__(*args, directory=str(MEDIA), **kwargs)
    def log_message(self, *args): pass
    def end_headers(self):
        self.send_header('Access-Control-Allow-Origin', '*')
        self.send_header('Cache-Control', 'no-store')
        super().end_headers()
    def do_GET(self):
        HITS.append({'url': self.path, 'host': self.headers.get('Host'), 'referer': self.headers.get('Referer', '')})
        p = urlsplit(self.path)
        if p.path == '/redirect':
            self.send_response(302); self.send_header('Location', parse_qs(p.query)['to'][0]); self.end_headers(); return
        if p.path == '/echo':
            data = b'ok'
        elif p.path == '/sub.vtt':
            data = b'WEBVTT\n\n00:00.000 --> 00:06.000\nSYNTHETIC SUBTITLE\n'
        elif p.path.startswith('/PRIVATE_PATH_SECRET'):
            data = b'<!doctype html><meta name="referrer" content="unsafe-url"><video id="v" src="/clip.mp4" autoplay muted controls></video>'
        else:
            return super().do_GET()
        self.send_response(200); self.send_header('Content-Type', 'text/html' if p.path.startswith('/PRIVATE') else 'text/plain')
        self.send_header('Content-Length', str(len(data))); self.end_headers(); self.wfile.write(data)

def hook(code):
    # These probes exist only in the installed test copy, never the distributed file.
    code = code.replace("root = hostEl.attachShadow({ mode: 'closed' });", "root = hostEl.attachShadow({ mode: 'open' }); hostEl.setAttribute('data-privacy-test','');")
    code = code.replace('    function subFile() {', '    st.__privacySubtitle = subPick;\n    function subFile() {')
    probe = '''
    document.addEventListener('__privacyRun', async () => {
      const node = document.getElementById('__privacyRequest');
      try {
        const q=JSON.parse(node.textContent); let result;
        if(q.kind==='gm') result=await gmGet(q.url,-1,-1,false,true);
        else if(q.kind==='resolve') result={url:await finalUrlGM(q.url,gmXhr())};
        else if(q.kind==='open') {
          const saved=playerSources; playerSources=()=>[q.url];
          try { openBrowserPlayer(document.getElementById('v')); } finally { playerSources=saved; }
          result={ok:true};
        } else if(q.kind==='subtitle') {vp.__privacySubtitle({kind:'url',src:q.url,label:'Fixture'});result={ok:true};}
        else if(q.kind==='download') {directDownload(q.url);result={ok:true};}
        else result={cur:vp&&vp.cur,hls:vp&&vp.hls&&vp.hls.constructor.version};
        node.dataset.done=JSON.stringify(result);
      } catch(e) {node.dataset.done=JSON.stringify({error:String(e)});}
    });
'''
    pos=code.rfind('})();'); assert pos>0
    return code[:pos]+probe+code[pos:]

def command(page, q):
    return page.evaluate('''q=>new Promise((resolve,reject)=>{
      let n=document.getElementById('__privacyRequest');if(!n){n=document.createElement('pre');n.id='__privacyRequest';document.body.append(n);}
      delete n.dataset.done;n.textContent=JSON.stringify(q);
      const timer=setTimeout(()=>{mo.disconnect();reject(new Error('manager probe timeout'));},20000);
      const mo=new MutationObserver(()=>{if(n.dataset.done){clearTimeout(timer);mo.disconnect();resolve(JSON.parse(n.dataset.done));}});
      mo.observe(n,{attributes:true});document.dispatchEvent(new Event('__privacyRun'));
    })''', q)

def kadri_request(page, url, ref):
    return page.evaluate('''({url,ref})=>new Promise((resolve,reject)=>{
      const id=String(Math.random()), timer=setTimeout(()=>reject(new Error('Kadri timeout')),15000);
      const f=e=>{if(e.data?.kadriTestRes?.id===id){clearTimeout(timer);window.removeEventListener('message',f);resolve(e.data.kadriTestRes);}};
      window.addEventListener('message',f);window.postMessage({kadriTestXhr:{id,url,headers:{Referer:ref},timeout:12000}},'*');
    })''', {'url':url,'ref':ref})

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--userscript', action='store_true');parser.add_argument('--kadri', action='store_true');args=parser.parse_args()
    assert args.userscript != args.kadri
    with tempfile.TemporaryDirectory(prefix='privacy-') as tmp:
        cert, key = pathlib.Path(tmp)/'cert.pem', pathlib.Path(tmp)/'key.pem'
        subprocess.run(['openssl','req','-x509','-newkey','rsa:2048','-nodes','-keyout',str(key),'-out',str(cert),'-days','1','-subj','/CN=TEST-ONLY'],check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        plain=http_server.ThreadingHTTPServer(('127.0.0.1',0), Handler)
        secure=http_server.ThreadingHTTPServer(('127.0.0.1',0), Handler)
        tls=ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER);tls.load_cert_chain(cert,key);secure.socket=tls.wrap_socket(secure.socket,server_side=True)
        for s in (plain,secure): threading.Thread(target=s.serve_forever,daemon=True).start()
        http=f'http://a.test:{plain.server_port}';https=f'https://a.test:{secure.server_port}';foreign=f'https://b.test:{secure.server_port}'
        ext=os.environ['VM_DIR' if args.userscript else 'EXT_DIR']
        manifest=json.loads((pathlib.Path(ext)/'manifest.json').read_text())
        profile=pathlib.Path(tmp)/'profile';(profile/'Default').mkdir(parents=True)
        (profile/'Default/Preferences').write_text(json.dumps({'extensions':{'ui':{'developer_mode':True}}}))
        with sync_playwright() as p:
            context=p.chromium.launch_persistent_context(str(profile),headless=False,ignore_https_errors=True,accept_downloads=True,
                args=['--disable-extensions-except='+ext,'--load-extension='+ext,'--ignore-certificate-errors','--autoplay-policy=no-user-gesture-required','--host-resolver-rules=MAP *.test 127.0.0.1','--no-proxy-server'])
            context.set_default_timeout(25000)
            worker=context.service_workers[0] if context.service_workers else context.wait_for_event('serviceworker')
            extension_origin=worker.url.split('/',3)[:3];extension_origin='/'.join(extension_origin)
            if args.userscript:
                options=context.new_page();options.goto(extension_origin+'/'+manifest.get('options_ui',{}).get('page','options/index.html'))
                code=hook((ROOT/'userscript/video-assistent.user.js').read_text())
                # Manager's own installation/update command, from its extension origin only.
                def install(body):
                    result=options.evaluate("async code=>await chrome.runtime.sendMessage({cmd:'ParseScript',data:{code}})",body)
                    print('VM ParseScript:',json.dumps(result)[:900],flush=True)
                    assert result is not None and not result.get('error'),result
                    return result
                first=install(code.replace('// @version      19.29','// @version      19.28'))
                updated=install(code)
                assert first['where']['id']==updated['where']['id'] and first.get('isNew') and not updated.get('isNew'), (first.get('where'),updated.get('where'))
                assert updated['update']['meta']['version']=='19.29',updated.get('update')
                print('PASS: real Violentmonkey installs and updates the bundled userscript',flush=True)
            page=context.new_page();page.goto(https+'/PRIVATE_PATH_SECRET?token=QUERY_SECRET#FRAGMENT_SECRET');page.wait_for_timeout(700)
            source=page.url.split('#')[0]
            ref=(source+'#FRAGMENT_SECRET').replace('https://','https://user:USERINFO_SECRET@',1)
            signed='/echo?sig=SIGNED%2Ffixture%2Bvalue&order=2&order=1'
            cases=[(https+signed, source if args.kadri else ''), (foreign+signed, https+'/' if args.kadri else ''), (http+signed,'')]
            for target,expected in cases:
                n=len(HITS)
                r=command(page,{'kind':'gm','url':target}) if args.userscript else kadri_request(page,target,ref)
                assert r.get('st',r.get('status'))==200,r
                hit=next(h for h in reversed(HITS[n:]) if h['url']==signed)
                assert hit['referer']==expected,(hit,expected)
                assert hit['url']==signed
            for dest in (foreign+signed,http+signed):
                n=len(HITS);target=https+'/redirect?to='+quote(dest,safe='')
                r=command(page,{'kind':'gm','url':target}) if args.userscript else kadri_request(page,target,ref)
                assert r.get('st',r.get('status'))==200,r
                hit=next(h for h in reversed(HITS[n:]) if h['url']==signed)
                assert 'SECRET' not in hit['referer'],hit
                if dest.startswith('http:'):assert not hit['referer'],hit
            print('PASS: real HTTP/HTTPS headers, redirects, downgrade, userinfo/fragment, signed query',flush=True)
            if args.userscript:
                # Test both auto-redirecting adapters and the HTTP-page origin ceiling.
                r=command(page,{'kind':'resolve','url':https+'/redirect?to='+quote(foreign+signed,safe='')})
                assert r['url']==foreign+signed,r
                page.goto(http+'/PRIVATE_PATH_SECRET?token=QUERY_SECRET#FRAGMENT_SECRET');page.wait_for_timeout(500)
                r=command(page,{'kind':'gm','url':foreign+signed});assert r['st']==200,r
                assert HITS[-1]['referer']==http+'/',HITS[-1]
                page.wait_for_function("document.getElementById('v').readyState>=2")
                page.evaluate("document.getElementById('v').muted=false")
                for media in ('/clip.mp4?sig=SIGNED%2Fmp4','/index.m3u8?sig=SIGNED%2Fhls','/clip.mp4?source=changed'):
                    r=command(page,{'kind':'open','url':http+media});assert not r.get('error'),r
                    page.wait_for_function("()=>{const v=document.querySelector('[data-privacy-test]')?.shadowRoot.querySelector('.vv');return v&&v.currentTime>0.3&&v.readyState>=2;}")
                    state=command(page,{'kind':'state'});assert state['cur']==http+media,state
                    if '.m3u8' in media:assert state['hls']=='1.7.3',state
                command(page,{'kind':'subtitle','url':foreign+'/sub.vtt'})
                page.wait_for_function("()=>document.querySelector('[data-privacy-test]').shadowRoot.textContent.includes('SYNTHETIC SUBTITLE')")
                with page.expect_download() as download:
                    command(page,{'kind':'download','url':http+'/clip.mp4?sig=SIGNED%2Fdownload'})
                saved=pathlib.Path(tmp)/'download.mp4';download.value.save_as(saved)
                assert saved.read_bytes()==(MEDIA/'clip.mp4').read_bytes()
                print('PASS: installed userscript MP4, HLS 1.7.3, subtitles, source change, byte-identical download',flush=True)
            context.close()
        plain.shutdown();secure.shutdown()
        out=ROOT/'validation-results';out.mkdir(exist_ok=True)
        (out/('privacy-userscript.json' if args.userscript else 'privacy-kadri.json')).write_text(json.dumps(HITS,indent=2))
if __name__=='__main__':main()
