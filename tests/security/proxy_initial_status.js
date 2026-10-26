'use strict';
// Execute the actual helper() and proxyLoop(), including GM timeout/error callbacks.
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const root=path.resolve(process.argv[2]||process.cwd()),source=fs.readFileSync(path.join(root,'src/080-helper-app.js'),'utf8');
const a=source.indexOf('  const HELPER ='),b=source.indexOf('  // Koppel-Seite',a);assert(a>=0&&b>a);
const tick=async()=>{for(let i=0;i<80;i++)await Promise.resolve();};
function clock(){let next=0,now=0;const timers=new Map();return {set(fn,ms){timers.set(++next,{fn,at:now+ms});return next;},clear(id){timers.delete(id);},async advance(ms){now+=ms;for(;;){const entry=[...timers].find(([,v])=>v.at<=now);if(!entry)break;timers.delete(entry[0]);entry[1].fn();await tick();}},timers};}
function harness(){
 const time=clock(),calls=[],media=[],uploads=[],states=new Map(),pulled=new Set(),running=[];let cleaning=false;
 const xhr=o=>{
  const u=new URL(o.url),local=u.origin==='http://127.0.0.1:47811',job=+(u.searchParams.get('job')||u.searchParams.get('id')||u.pathname.slice(1));let finished=false;
  const abort=()=>{if(finished)return;finished=true;o.onabort&&o.onabort();};
  calls.push({local,path:u.pathname,job});
  if(local)assert.equal(o.headers['X-EVP-Key'],'0123456789abcdef0123456789abcdef');
  queueMicrotask(()=>{
   if(finished||cleaning)return;
   if(!local){finished=true;media.push(job);o.onload({status:200,responseHeaders:'Content-Length: 1',response:new ArrayBuffer(1)});return;}
   let reply;
   if(u.pathname==='/pull'){
    if(pulled.has(job))reply={ok:true,done:true};
    else{pulled.add(job);reply={ok:true,reqs:[{id:1,u:'https://media.test/'+job,o:-1,l:-1,s:false}]};}
   }else if(u.pathname==='/dlstate'){
    const next=states.get(job).shift()||'active';
    if(next==='timeout'||next==='error'){finished=true;o[next==='timeout'?'ontimeout':'onerror']();return;}
    reply={ok:true,state:next==='active'?'startet':next};
   }else if(u.pathname==='/push'){uploads.push(job);assert.equal(o.data.byteLength,1);reply={ok:true};}
   else throw Error('Unexpected control request '+u.pathname);
   finished=true;o.onload({status:200,responseText:JSON.stringify(reply)});
  });return {abort};
 };
 const api=new Function('CONFIG','GM_getValue','gmXhr','mediaReferrer','baseDomain','hostOf','setTimeout','clearTimeout',source.slice(a,b)+'return {proxyLoop,cancelProxyJob,proxyJobs,stats:()=>({active:proxyActive,queued:proxyWaiting.length,jobs:proxyJobs.size})};')({systemHelper:true},()=> '0123456789abcdef0123456789abcdef',()=>xhr,()=>'',x=>x,()=> 'media.test',time.set,time.clear);
 return {api,time,calls,media,uploads,start(job,status){states.set(job,[...status]);const f=api.proxyLoop(job,['media.test']);running.push(f);return f;},count(job,path){return calls.filter(c=>c.local&&c.job===job&&c.path===path).length;},async clean(){cleaning=true;for(const state of api.proxyJobs.values())api.cancelProxyJob(state);await Promise.allSettled(running);assert.deepEqual(api.stats(),{active:0,queued:0,jobs:0});assert.equal(time.timers.size,0,'Leaked timer/abort wait');}};
}
(async()=>{
 for(const first of ['timeout','error']){
  const h=harness();try{
   const pending=h.start(1,[first,'active']);await tick();assert.equal(h.count(1,'/pull'),1);assert.equal(h.media.length,0);
   await h.time.advance(2000);await pending;
   assert.deepEqual(h.media,[1],'initial status timeout discarded pulled work');assert.deepEqual(h.uploads,[1]);assert.equal(h.count(1,'/dlstate'),2);assert.equal(h.count(1,'/pull'),2,'batch was pulled twice before processing or completion missing');
   const status=h.calls.filter(c=>c.local&&c.job===1).map(c=>c.path);assert.deepEqual(status,['/pull','/dlstate','/dlstate','/push','/pull']);
  }finally{await h.clean();}
 }
 for(const terminal of ['abgebrochen','fertig','Fehler: HTTP 422']){
  const h=harness();try{await h.start(1,[terminal,'active']);assert.equal(h.count(1,'/dlstate'),1);assert.equal(h.count(1,'/pull'),1);assert.equal(h.media.length,0);assert.equal(h.uploads.length,0);}finally{await h.clean();}
 }
 {
  const h=harness();try{
   const missing=h.start(1,['timeout','error','timeout']);await tick();await h.time.advance(2000);await h.time.advance(2000);await missing;
   assert.equal(h.count(1,'/dlstate'),3);assert.equal(h.count(1,'/pull'),1);assert.equal(h.media.length,0);assert.equal(h.uploads.length,0);const count=h.calls.length;await h.time.advance(60000);assert.equal(h.calls.length,count,'unbounded status retry');
  }finally{await h.clean();}
 }
 {
  const h=harness();try{const stopped=h.start(1,['timeout','abgebrochen']);await tick();await h.time.advance(2000);await stopped;assert.equal(h.count(1,'/dlstate'),2);assert.equal(h.media.length,0);}finally{await h.clean();}
 }
 {
  const h=harness();try{
   let firstDone=false;const first=h.start(1,['timeout','active']).then(()=>{firstDone=true;});await tick();
   await h.api.proxyLoop(1,['media.test']);assert.equal(h.count(1,'/pull'),1,'duplicate invocation re-pulled pending work');
   await h.start(2,['active']);assert.equal(firstDone,false,'unknown initial status became terminal');assert.deepEqual(h.media,[2],'healthy parallel job was blocked');
   await h.time.advance(2000);await first;assert.deepEqual(h.media,[2,1]);assert.deepEqual(h.uploads,[2,1]);assert.equal(h.count(2,'/dlstate'),1,'healthy job made extra status queries');
  }finally{await h.clean();}
 }
 console.log('PASS: actual helper/proxyLoop retry initial timeout/error without re-pull, duplicate GET/upload or slot leak; confirmed terminals and three-error limit stop; other jobs proceed');
})().then(()=>{finished=true;}).catch(e=>{console.error(e);process.exitCode=1;}).finally(()=>clearTimeout(deadline));
let finished=false;const deadline=setTimeout(()=>{console.error('Initial status regression did not settle');process.exitCode=1;},8000);
process.on('beforeExit',()=>{if(!finished&&!process.exitCode)process.exitCode=1;});
