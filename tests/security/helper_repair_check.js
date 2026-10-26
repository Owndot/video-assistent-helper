'use strict';
// Actual adapter + helper() code; deterministic persistent-read/event scheduling.
const fs=require('node:fs'),path=require('node:path'),vm=require('node:vm'),assert=require('node:assert/strict');
const args=process.argv.slice(2),root=path.resolve(args.find(x=>!x.startsWith('--'))||path.join(__dirname,'../..'));
const only=(args.find(x=>x.startsWith('--case='))||'').slice(7);
const selected=(args.find(x=>x.startsWith('--target='))||'').slice(9);
assert(!selected || ['chrome','firefox'].includes(selected));
const OLD='0123456789abcdef0123456789abcdef',NEW='abcdef0123456789abcdef0123456789',BAD='ffffffffffffffffffffffffffffffff';
const source=fs.readFileSync(path.join(root,'src/080-helper-app.js'),'utf8');
const start=source.indexOf('  const HELPER ='),end=source.indexOf('  // ---------- Laden über den Browser',start);
assert(start>=0&&end>start);const body=source.slice(start,end);
const tick=async()=>{for(let i=0;i<12;i++)await Promise.resolve();};
function event(){const listeners=new Set();return {addListener:f=>listeners.add(f),removeListener:f=>listeners.delete(f),emit:(...a)=>[...listeners].forEach(f=>f(...a))};}
function hub(){
 const changed=event(),data={'gm:helperKey':OLD,'gm:settings':'original'},requests=[],reads=[],deliveries=[];
 let accepted=OLD;
 const h={changed,data,requests,reads,holdReads:false,failRead:false,initRace:false};
 const local={get(keys){
   const snapshot=keys===null?{...data}:{'gm:helperKey':data['gm:helperKey']};
   if(keys===null){if(h.initRace){h.change(NEW,true);h.initRace=false;}return Promise.resolve(snapshot);}
   return new Promise((resolve,reject)=>{const read={snapshot,resolve:()=>resolve(snapshot),reject:()=>reject(Error('synthetic read failure'))};reads.push(read);if(h.failRead)read.reject();else if(!h.holdReads)read.resolve();});
 },set:async values=>{for(const[k,v]of Object.entries(values)){const oldValue=data[k];data[k]=v;deliveries.push(()=>changed.emit({[k]:{oldValue,newValue:v}},'local'));}},remove:async key=>{const oldValue=data[key];delete data[key];deliveries.push(()=>changed.emit({[key]:{oldValue}},'local'));}};
 h.change=(key,emit=false)=>{const oldValue=data['gm:helperKey'];data['gm:helperKey']=key;accepted=key;const send=()=>changed.emit({'gm:helperKey':{oldValue,newValue:key}},'local');if(emit)send();else deliveries.push(send);};
 h.flush=()=>{while(deliveries.length)deliveries.shift()();};h.accept=k=>{accepted=k;};
 h.api={storage:{local,onChanged:changed},runtime:{id:'synthetic-extension',onMessage:event(),connect:()=>{
  const port={onMessage:event(),onDisconnect:event(),disconnect(){port.onDisconnect.emit();},postMessage(m){if(m.t!=='req')return;requests.push(m);Promise.resolve().then(()=>port.onMessage.emit({t:'done',status:m.headers['X-EVP-Key']===accepted?200:403,text:'{"ok":true}'}));}};return port;
 }}};
 return h;
}
function tab(target,h){
 const context={KADRI_VERSION:'synthetic',CONFIG:{systemHelper:true},setTimeout:()=>0,clearTimeout(){},console,URL,Promise,Uint8Array,ArrayBuffer,Blob,atob,btoa};
 context[target==='chrome'?'chrome':'browser']=h.api;
 const pre=fs.readFileSync(path.join(root,target,'prelude.js'),'utf8'),epi=fs.readFileSync(path.join(root,target,'epilogue.js'),'utf8');
 vm.runInNewContext(pre+'\nconst gmXhr=()=>GM_xmlhttpRequest;\n'+body+'\nglobalThis.testHelper=helper;globalThis.testPreference=()=>GM_getValue("settings");\n'+epi,context);
 return context;
}
async function extension(target){
 if(!only||only==='event-race'){
  const h=hub(),a=tab(target,h),b=tab(target,h);await tick();assert.notEqual(await a.testHelper('/ping'),null);
  h.change(NEW); // Persistent write committed, onChanged deliberately NOT delivered.
  assert.notEqual(await a.testHelper('/beat'),null,target+': request before storage event used stale key');
  assert.notEqual(await b.testHelper('/ping'),null,target+': second tab used stale key');
  h.changed.emit({'gm:helperKey':{newValue:BAD}},'sync');h.flush();
  assert.notEqual(await a.testHelper('/ping'),null);
  console.log('PASS: '+target+' persistent read before delayed events; multiple tabs; irrelevant storage area ignored');
 }
 if(!only||only==='initial-race'){
  const h=hub();h.initRace=true;const a=tab(target,h);await tick();
  // get(null) captured OLD, then write+event happened before start(cache).
  assert.notEqual(await a.testHelper('/ping'),null,target+': initialization lost storage change');
  h.data['gm:settings']='updated';assert.equal(a.testPreference(),'original','Other preferences changed behavior');
  console.log('PASS: '+target+' initial snapshot/listener gap cannot affect request key; other preferences unchanged');
 }
 if(only)return;
 const h=hub(),a=tab(target,h);await tick();
 await h.api.storage.local.remove('gm:helperKey');let count=h.requests.length;
 assert.equal(await a.testHelper('/ping'),null);assert.equal(h.requests.length,count,'Removed key fell back to cached/legacy key');
 for (const invalid of ['',null,'evp-1',123,{}]) {h.data['gm:helperKey']=invalid;assert.equal(await a.testHelper('/ping'),null);assert.equal(h.requests.length,count,'Invalid key sent a fallback');}
 h.change(OLD);h.accept(NEW);h.failRead=true;
 assert.equal(await a.testHelper('/ping'),null);assert.equal(h.requests.length,count,'Read error sent cached key');
 // Explicit candidate bypasses only its own read, never becomes trusted default.
 const readCount=h.reads.length;
 for (const candidate of ['',0,false]) {assert.equal(await a.testHelper('/ping',1200,null,candidate),null);assert.equal(h.reads.length,readCount);assert.equal(h.requests.length,count);}
 assert.notEqual(await a.testHelper('/ping',1200,null,NEW),null);assert.equal(h.reads.length,readCount);
 assert.equal(await a.testHelper('/ping',1200,null,BAD),null);assert.equal(await a.testHelper('/ping'),null);
 assert.equal(h.requests.length,count+2,'Candidate or read failure retried');
 assert.equal(h.data['gm:helperKey'],OLD,'Candidate replaced the persistent key');
 h.failRead=false;assert.equal(await a.testHelper('/ping'),null);assert.equal(h.requests.at(-1).headers['X-EVP-Key'],OLD);
 assert.equal(h.requests.length,count+3,'Candidate leaked into another request or triggered retry');
 h.failRead=false;h.change(OLD);h.holdReads=true;
 const first=a.testHelper('/beat');await tick();const r1=h.reads.at(-1);
 h.change(NEW);const second=a.testHelper('/vol');await tick();const r2=h.reads.at(-1);
 count=h.requests.length;r2.resolve();assert.notEqual(await second,null);r1.resolve();assert.equal(await first,null);
 assert.deepEqual(h.requests.slice(count).map(x=>x.headers['X-EVP-Key']),[NEW,OLD],'In-flight request snapshot changed');
 assert.equal(h.requests.length,count+2,'Old request retried after rejection');
 h.holdReads=false;const restarted=tab(target,h);await tick();assert.notEqual(await restarted.testHelper('/ping'),null);
 a.CONFIG.systemHelper=false;count=h.reads.length;assert.equal(await a.testHelper('/ping'),null);assert.equal(h.reads.length,count);
 console.log('PASS: '+target+' removal/read failures abort; candidates isolated; parallel snapshots immutable; no retry; restart and opt-out');
}
async function userscript(){
 let key=OLD,accepted=OLD,broken=false;const requests=[];
 const c={CONFIG:{systemHelper:true},setTimeout:()=>0,clearTimeout(){},GM_getValue:()=>{if(broken)throw Error('synthetic storage error');return key;},gmXhr:()=>o=>{requests.push(o);o.onload({status:o.headers['X-EVP-Key']===accepted?200:403,responseText:'{"ok":true}'});}};
 vm.runInNewContext(body+'\nglobalThis.testHelper=helper;',c);
 assert.notEqual(await c.testHelper('/ping'),null);key=NEW;accepted=NEW;assert.notEqual(await c.testHelper('/ping'),null,'userscript kept stale key');
 assert.equal(await c.testHelper('/ping',1200,null,BAD),null);broken=true;const before=requests.length;assert.equal(await c.testHelper('/ping'),null);assert.equal(requests.length,before);
 broken=false;c.CONFIG.systemHelper=false;assert.equal(await c.testHelper('/ping'),null);assert.equal(requests.length,before);
 console.log('PASS: userscript GM path remains compatible; candidate/error/opt-out behavior retained');
}
(async()=>{if(fs.existsSync(path.join(root,'chrome/prelude.js'))){for (const target of selected?[selected]:['chrome','firefox']) await extension(target);}if(!only)await userscript();})().catch(e=>{console.error(e);process.exitCode=1;});
