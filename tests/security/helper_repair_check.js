'use strict';
// Actual production helper()/extension adapters, synthetic keys, no sockets or browser emulation claim.
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const root = path.resolve(process.argv[2] || path.join(__dirname, '../..'));
const OLD = '0123456789abcdef0123456789abcdef';
const NEW = 'abcdef0123456789abcdef0123456789';
const BAD = 'ffffffffffffffffffffffffffffffff';
const source = fs.readFileSync(path.join(root, 'src/080-helper-app.js'), 'utf8');
const start = source.indexOf('  const HELPER =');
const end = source.indexOf('  // ---------- Laden über den Browser', start);
assert(start >= 0 && end > start);
const body = source.slice(start, end);
const tick = async () => { for (let i=0;i<8;i++) await Promise.resolve(); };
function event() {
  const listeners = new Set();
  return { addListener: f => listeners.add(f), removeListener: f => listeners.delete(f), emit: (...a) => [...listeners].forEach(f=>f(...a)) };
}
function hub() {
  const changed=event(), data={'gm:helperKey':OLD}, requests=[];
  let accepted=OLD;
  const local={get:async()=>({...data}),set:async values=>{
    const delta={};for(const [k,v]of Object.entries(values)){delta[k]={oldValue:data[k],newValue:v};data[k]=v;}
    changed.emit(delta,'local');
  },remove:async key=>{const oldValue=data[key];delete data[key];changed.emit({[key]:{oldValue}},'local');}};
  const api={storage:{local,onChanged:changed},runtime:{id:'synthetic-extension',onMessage:event(),connect:()=>{
    const port={onMessage:event(),onDisconnect:event(),disconnect(){port.onDisconnect.emit();},postMessage(m){
      if(m.t!=='req')return;
      requests.push(m);
      Promise.resolve().then(()=>port.onMessage.emit({t:'done',status:m.headers['X-EVP-Key']===accepted?200:403,text:'{"ok":true}'}));
    }};return port;
  }}};
  return {api,data,requests,changed,local,accept:k=>{accepted=k;}};
}
async function extension(target) {
  const h=hub();
  const pre=fs.readFileSync(path.join(root,target,'prelude.js'),'utf8');
  const epi=fs.readFileSync(path.join(root,target,'epilogue.js'),'utf8');
  function tab(){
    const context={KADRI_VERSION:'synthetic',CONFIG:{systemHelper:true},setTimeout:()=>0,clearTimeout(){},console,URL,Promise,Uint8Array,ArrayBuffer,Blob,atob,btoa};
    context[target==='chrome'?'chrome':'browser']=h.api;
    vm.runInNewContext(pre+'\nconst gmXhr = () => GM_xmlhttpRequest;\n'+body+'\nglobalThis.testHelper=helper;\n'+epi,context);
    return context;
  }
  const a=tab(),b=tab();await tick();
  assert.deepEqual(JSON.parse(JSON.stringify(await a.testHelper('/ping'))),{ok:true});
  h.accept(NEW);
  assert.equal(await a.testHelper('/ping'),null,'Invalidated old key must still be rejected');
  await h.local.set({'gm:helperKey':NEW});await tick();
  assert.notEqual(await a.testHelper('/beat'),null,target+': existing tab kept stale key after re-pairing');
  assert.notEqual(await b.testHelper('/ping'),null,target+': second tab kept stale key');
  assert.equal(h.requests.at(-1).headers['X-EVP-Key'],NEW);
  // A different storage area must never overwrite the trusted local key cache.
  h.changed.emit({'gm:helperKey':{newValue:BAD}},'sync');
  assert.notEqual(await a.testHelper('/ping'),null);
  await h.local.remove('gm:helperKey');await tick();
  assert.equal(await a.testHelper('/ping'),null,'Removed key must not survive in tab memory');
  assert.equal(h.requests.at(-1).headers['X-EVP-Key'],'evp-1'); // Current genuine Helper rejects this existing pre-pair placeholder.
  await h.local.set({'gm:helperKey':NEW});await tick();
  const restarted=tab();await tick();assert.notEqual(await restarted.testHelper('/ping'),null);
  const override=await a.testHelper('/ping',1200,null,BAD);
  assert.equal(override,null);assert.equal(h.data['gm:helperKey'],NEW,'Candidate must not become stored identity');
  console.log('PASS: '+target+' actual adapter: re-pair across open tabs, revocation, storage-area isolation, restart and invalid override');
}
async function userscript() {
  let key=OLD, accepted=OLD, broken=false;const requests=[];
  const c={CONFIG:{systemHelper:true},setTimeout:()=>0,GM_getValue:()=>{if(broken)throw Error('synthetic storage error');return key;},gmXhr:()=>o=>{requests.push(o);o.onload({status:o.headers['X-EVP-Key']===accepted?200:403,responseText:'{"ok":true}'});}};
  vm.runInNewContext(body+'\nglobalThis.testHelper=helper;',c);
  assert.notEqual(await c.testHelper('/ping'),null);
  key=NEW;accepted=NEW;assert.notEqual(await c.testHelper('/ping'),null,'userscript kept stale key');
  assert.equal(await c.testHelper('/ping',1200,null,BAD),null);
  broken=true;const before=requests.length;assert.equal(await c.testHelper('/ping'),null);assert.equal(requests.length,before,'Storage failure must not send a stale key');
  broken=false;c.CONFIG.systemHelper=false;assert.equal(await c.testHelper('/ping'),null);assert.equal(requests.length,before);
  console.log('PASS: actual helper(): fresh GM storage, candidate rejection, storage-error fail closed, helper opt-out');
}
(async()=>{if(fs.existsSync(path.join(root,'chrome/prelude.js'))){await extension('chrome');await extension('firefox');}await userscript();})().catch(e=>{console.error(e);process.exitCode=1;});
