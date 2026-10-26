'use strict';
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const arg=process.argv.slice(2),root=path.resolve(arg.find(x=>!x.startsWith('--'))||process.cwd()),which=(arg.find(x=>x.startsWith('--case='))||'').slice(7);
const source=fs.readFileSync(path.join(root,'src/080-helper-app.js'),'utf8');
const a=source.indexOf('  function gmGet('),b=source.indexOf('  // Koppel-Seite',a);assert(a>=0&&b>a);
function api(xhr,helper,clock){return new Function('gmXhr','mediaReferrer','helper','baseDomain','hostOf','setTimeout','clearTimeout',source.slice(a,b)+'return {gmGet,proxyLoop};')(()=>xhr,()=>'',helper,x=>x,()=> 'fixture.test',clock?clock.set:setTimeout,clock?clock.clear:clearTimeout);}
const tick=async()=>{for(let i=0;i<25;i++)await Promise.resolve();};
async function size(){
 for(const validator of ['ETag: "unchanged"\r\n','']){
  let step=0;const uploads=[];
  const helper=async(p,t,data)=>{
   if(p.startsWith('/pull'))return ++step===1?{reqs:[{id:1,u:'http://fixture/file',o:0,l:1,s:true}]}:step===2?{reqs:[{id:2,u:'http://fixture/file',o:0,l:8,s:false}]}:{done:true};
   if(p.startsWith('/dlstate'))return {ok:true,state:'startet'};
   uploads.push({st:+new URL(p,'http://helper').searchParams.get('st'),bytes:data.byteLength});return {ok:true};
  };
  const client=api(o=>{let stopped=false;const handle={abort(){stopped=true;o.onabort&&o.onabort();}};queueMicrotask(()=>{
   if(o.headers.Range==='bytes=0-0'){
    const h=validator+'Content-Length: 1\r\nContent-Range: bytes 0-0/8';
    if(o.onreadystatechange)o.onreadystatechange({readyState:2,status:206,responseHeaders:h});
    if(!stopped)o.onload({status:206,responseHeaders:h,response:new ArrayBuffer(1)});
   }else o.onload({status:206,responseHeaders:validator+'Content-Length: 6\r\nContent-Range: bytes 0-5/6',response:new ArrayBuffer(6)});
  });return handle;},helper);
  await client.proxyLoop(1,['fixture.test']);assert.equal(uploads.at(-1).st,422,'client accepted eight-versus-six bytes with unchanged ETag');assert.equal(uploads.at(-1).bytes,0);
 }
 const c=api(o=>{queueMicrotask(()=>o.onload({status:206,responseHeaders:'Content-Length: 8\r\nContent-Range: bytes 0-7/*\r\nETag: "same"',response:new ArrayBuffer(8)}));return {abort(){}};},async()=>null);
 assert.equal((await c.gmGet('http://fixture/file',0,8,false,false,'"same"',8)).st,206);
 console.log('PASS: actual client pins probe total with/without ETag, rejects eight-to-six shrink, accepts exact window with absent total');
}
async function queue(){
 function clock(){let id=0,now=0;const timers=new Map();return {set(fn,ms){timers.set(++id,{fn,at:now+ms});return id;},clear(n){timers.delete(n);},async advance(ms){now+=ms;for(;;){const due=[...timers].find(([,t])=>t.at<=now);if(!due)break;timers.delete(due[0]);due[1].fn();await tick();}},timers};}
 function harness(){
  const time=clock(),started=[],aborted=[],states=new Map(),batches=new Map(),refused=new Set(),missed=new Set(),handles=[],uploads=[];let cleaning=false,active=0,peak=0;
  const helper=async(p,t,data)=>{
   const u=new URL(p,'http://helper'),job=+(u.searchParams.get('job')||u.searchParams.get('id'));
   if(u.pathname==='/pull')return cleaning?{done:true}:batches.get(job)?.length?{reqs:batches.get(job).shift()}:{done:true};
   if(u.pathname==='/dlstate'){if(missed.delete(job))return null;return {ok:true,state:states.get(job)};}
   uploads.push(job);return {ok:!refused.has(job)};
  };
  const client=api(o=>{
   started.push(o.url);active++;peak=Math.max(peak,active);let done=false;
   const finish=kind=>{if(done)return;done=true;active--;if(kind==='load')o.onload({status:200,responseHeaders:'Content-Length: 1',response:new ArrayBuffer(1)});else o[kind]();};
   const h={url:o.url,finish:()=>finish('load'),error:()=>finish('onerror'),timeout:()=>finish('ontimeout'),abort(){if(done)return;aborted.push(o.url);finish('onabort');o.onload({status:200,responseHeaders:'Content-Length: 1',response:new ArrayBuffer(1)});o.onerror();}};
   handles.push(h);if(cleaning)queueMicrotask(()=>h.abort());return h;
  },helper,time);
  const running=[];
  return {time,client,started,aborted,uploads,refused,missed,states,handles,
   start(job,count=1){states.set(job,'startet');batches.set(job,[Array.from({length:count},(_,id)=>({id:id+1,u:'http://fixture/'+job+'-'+id,o:-1,l:-1,s:false}))]);const f=client.proxyLoop(job,['fixture.test']);running.push(f);return f;},
   async clean(){cleaning=true;handles.forEach(h=>h.abort());await Promise.allSettled(running);assert.equal(active,0);assert(peak<=2,'Slot count leaked or exceeded two');}
  };
 }
 {
  const h=harness();try{
   h.refused.add(1);const old=h.start(1,4);await tick();assert.equal(h.started.length,2);
   h.handles[0].finish();await tick();const fresh=h.start(2);await tick();
   assert(!h.started.includes('http://fixture/1-2')&&!h.started.includes('http://fixture/1-3'),'cancelled queued GM work started');
   assert(h.started.includes('http://fixture/2-0'),'new job blocked behind cancelled work');
   assert(h.aborted.includes('http://fixture/1-1'),'active peer was not aborted');
   h.handles.find(x=>x.url==='http://fixture/2-0').finish();await Promise.all([old,fresh]);
   const reuse=h.start(3,3);await tick();assert(h.started.includes('http://fixture/3-0')&&h.started.includes('http://fixture/3-1'),'both slots were not restored');assert(!h.started.includes('http://fixture/3-2'),'slot released twice');
   h.handles.find(x=>x.url==='http://fixture/3-0').finish();await tick();assert(h.started.includes('http://fixture/3-2'));h.handles.find(x=>x.url==='http://fixture/3-1').finish();h.handles.find(x=>x.url==='http://fixture/3-2').finish();await reuse;
  }finally{await h.clean();}
 }
 {
  const h=harness();try{
   const old=h.start(1,4);await tick();h.missed.add(1);await h.time.advance(2000);assert.equal(h.aborted.length,0,'one status timeout cancelled a healthy job');h.states.set(1,'abgebrochen');await h.time.advance(2000);await tick();
   assert.equal(h.started.length,2,'status cancellation started queued work');assert.equal(h.aborted.length,2);
   const fresh=h.start(2);await tick();h.handles.find(x=>x.url==='http://fixture/2-0').finish();await Promise.all([old,fresh]);
  }finally{await h.clean();}
 }
 {
  const h=harness();try{
   const busy=h.start(1,2);await tick();const cancelled=h.start(2);await tick();h.states.set(2,'abgebrochen');await h.time.advance(2000);await tick();
   const next=h.start(3);await tick();h.handles[0].finish();await tick();assert(!h.started.includes('http://fixture/2-0'),'cancelled unacquired entry started');assert(h.started.includes('http://fixture/3-0'));
   h.handles.find(x=>x.url==='http://fixture/3-0').finish();h.handles.find(x=>x.url==='http://fixture/1-1').finish();await Promise.all([busy,cancelled,next]);
  }finally{await h.clean();}
 }
 {
  const h=harness();try{
   const missing=h.start(1,4);await tick();for(let n=0;n<3;n++){h.missed.add(1);await h.time.advance(2000);await tick();if(n<2)assert.equal(h.aborted.length,0);}
   assert.equal(h.aborted.length,2);assert.equal(h.started.length,2);await missing;
  }finally{await h.clean();}
 }
 for(const error of ['error','timeout']){
  const h=harness();try{
   const bad=h.start(1),good=h.start(2);await tick();
   h.handles.find(x=>x.url==='http://fixture/1-0')[error]();await tick();assert(!h.aborted.includes('http://fixture/2-0'),'other job was aborted');
   h.handles.find(x=>x.url==='http://fixture/2-0').finish();await Promise.all([bad,good]);
   const next=h.start(3);await tick();h.handles.find(x=>x.url==='http://fixture/3-0').finish();await next;
  }finally{await h.clean();}
 }
 {
  const h=harness();try{const signed=h.start(-2);await tick();assert(h.started.includes('http://fixture/-2-0'),'signed job ID was rejected');h.handles[0].finish();await signed;}finally{await h.clean();}
 }
 // Actual helper() upload abort handle, including late completion after cancellation.
 {
  const start=source.indexOf('  const HELPER ='),end=source.indexOf('  // ---------- Laden über den Browser',start),time=clock();let options,aborted=0;
  const helper=new Function('CONFIG','gmXhr','GM_getValue','setTimeout','clearTimeout',source.slice(start,end)+'return helper;')({systemHelper:true},()=>o=>{options=o;return {abort(){aborted++;o.onabort();}};},()=> '0123456789abcdef0123456789abcdef',time.set,time.clear);
  const state={cancelled:false,aborters:new Set()},pending=helper('/push?job=1',60000,new ArrayBuffer(2),null,state);await tick();
  state.cancelled=true;for(const abort of [...state.aborters])abort();assert.equal(await pending,null);assert.equal(aborted,1);options.onload({status:200,responseText:'{"ok":true}'});assert.equal(state.aborters.size,0);
 }
 console.log('PASS: actual rejected-upload/status cancellation drops queued batch, aborts active GET/POST, frees slots exactly once; independent jobs/errors/timeouts/late callbacks settle');
}
let finished=false;
const deadline=setTimeout(()=>{console.error('Review regression did not settle');process.exitCode=1;},8000);
(async()=>{if(!which||which==='size')await size();if(!which||which==='queue')await queue();finished=true;})().catch(e=>{console.error(e);process.exitCode=1;}).finally(()=>clearTimeout(deadline));
process.on('beforeExit',()=>{if(!finished&&!process.exitCode){console.error('Unresolved review regression');process.exitCode=1;}});
