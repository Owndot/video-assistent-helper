'use strict';
// Actual gmGet/proxyLoop source. GM callback transport is controlled, not a real manager.
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict'),crypto=require('node:crypto');
const root=path.resolve(process.argv[2]||process.cwd());
const src=fs.readFileSync(path.join(root,'src/080-helper-app.js'),'utf8');
const a=src.indexOf('  function gmGet('),b=src.indexOf('  // Koppel-Seite',a);assert(a>=0&&b>a);
function api(xhr,helper=async()=>null){return new Function('gmXhr','mediaReferrer','helper','baseDomain','hostOf',src.slice(a,b)+'return {gmGet,proxyLoop};')(()=>xhr,()=>'',helper,x=>x,()=> 'fixture.test');}
let allocate=0,aborts=0,last;
function response(status,headers,bytes=10,head=false){
 return api(o=>{last=o;let stopped=false;const handle={abort(){stopped=true;aborts++;o.onabort&&o.onabort({});}};
  queueMicrotask(()=>{
   if(head&&o.onreadystatechange)o.onreadystatechange({readyState:2,status,responseHeaders:headers});
   if(stopped)return;
   allocate++;const data=new ArrayBuffer(bytes);const v=new Uint8Array(data);for(let i=0;i<v.length;i++)v[i]=i;
   o.onload({status,responseHeaders:headers,response:data});
  });return handle;});
}
(async()=>{
 for(const headers of ['Content-Length: 67108864','Transfer-Encoding: chunked','']){
  allocate=0;aborts=0;const got=await response(200,headers,10,true).gmGet('http://fixture.test/movie',0,1,true,false);
  assert.equal(allocate,0,'size probe buffered its response body');assert.equal(aborts,1);assert.equal(got.total,headers.startsWith('Content-Length')?67108864:-1);
 }
 console.log('PASS: actual GM size probes abort at headers for known/unknown and ignored-Range responses');
 let got=await response(200,'Content-Length: 10').gmGet('http://fixture.test/movie?sig=UNCHANGED%2Fvalue',3,4,false,true);
 assert.equal(got.st,206);assert.deepEqual([...new Uint8Array(got.body)],[3,4,5,6]);assert.equal(got.total,10);assert.equal(last.url,'http://fixture.test/movie?sig=UNCHANGED%2Fvalue');assert.equal(last.headers.Range,'bytes=3-6');assert.equal(last.anonymous,true);
 got=await response(206,'Content-Length: 4\r\nContent-Range: bytes 3-6/10',4).gmGet('http://fixture.test/movie',3,4,false,false);assert.equal(got.st,206);
 got=await response(200,'Transfer-Encoding: chunked').gmGet('http://fixture.test/movie',-1,-1,false,false);assert.equal(got.body.byteLength,10);
 for(const [status,headers,bytes] of [[200,'Transfer-Encoding: chunked\r\nContent-Length: 4',10],[200,'Content-Length: 4',10],[200,'Content-Length: 10\r\nContent-Length: 4',10],[206,'Content-Range: bytes 4-7/10\r\nContent-Length: 4',4],[206,'Content-Range: bytes 3-5/10\r\nContent-Length: 3',3],[206,'Content-Range: bytes 3-6/6\r\nContent-Length: 4',4]]){
  assert.equal((await response(status,headers,bytes).gmGet('http://fixture.test/movie',3,4,false,false)).st,422,headers);
 }
 got=await response(206,'Content-Length: 4\r\nContent-Range: bytes 3-6/10\r\nETag: "v1"',4).gmGet('http://fixture.test/movie',3,4,false,false,'"v1"');assert.equal(got.st,206);assert.equal(last.headers['If-Range'],'"v1"');
 assert.equal((await response(200,'Content-Length: 10\r\nETag: "v2"').gmGet('http://fixture.test/movie',3,4,false,false,'"v1"')).st,422);
 for(const event of ['ontimeout','onabort','onerror']){
  let aborted=0;const client=api(o=>{queueMicrotask(()=>o[event]());return {abort(){aborted++;}};});
  assert.equal((await client.gmGet('http://fixture.test/movie',-1,-1,false,false)).st,{ontimeout:504,onabort:499,onerror:502}[event]);assert.equal(aborted,event==='onabort'?0:1);
 }
 console.log('PASS: bytes, 200/206/chunked, malformed framing/ranges, If-Range/changed validators, abort and timeout');
 let active=0,peak=0,peakArrays=0,posted=0;const pulled=new Set();const LENGTH=64<<20;
 const helper=async(p,to,body)=>{
  const u=new URL(p,'http://helper');
  if(u.pathname==='/dlstate')return {ok:true,state:'startet'};
  if(u.pathname==='/pull'){
   const job=u.searchParams.get('job');if(pulled.has(job))return {done:true};pulled.add(job);
   return {reqs:Array.from({length:4},(_,i)=>({id:i,u:'http://fixture.test/'+job+'-'+i,o:-1,l:-1,s:false}))};
  }
  assert.equal(body.byteLength,LENGTH);assert.equal(u.searchParams.get('st'),'200');
  const digest=crypto.createHash('sha256').update(new Uint8Array(body)).digest('hex');
  assert.equal(digest,expected);await new Promise(r=>setTimeout(r,3));active--;posted++;return {ok:true};
 };
 const expected=crypto.createHash('sha256').update(Buffer.alloc(LENGTH,7)).digest('hex');if(global.gc)global.gc();
 const client=api(o=>{const handle={abort(){}};queueMicrotask(()=>{
  if(global.gc)global.gc();active++;peak=Math.max(peak,active);
  const data=new ArrayBuffer(LENGTH);new Uint8Array(data).fill(7);peakArrays=Math.max(peakArrays,process.memoryUsage().arrayBuffers);
  o.onload({status:200,responseHeaders:'Content-Length: '+LENGTH,response:data});
 });return handle;},helper);
 await Promise.all([client.proxyLoop(1,['fixture.test']),client.proxyLoop(2,['fixture.test'])]);
 assert.equal(posted,8);assert.equal(active,0);assert.equal(peak,2,'full responses accumulated across jobs');
 console.log('MEASUREMENT '+JSON.stringify({payloadBytes:LENGTH,concurrentResponses:peak,peakNodeArrayBuffersBytes:peakArrays,nodeRssBytes:process.memoryUsage().rss,note:'GM callback fixture; ArrayBuffers are external to V8 old-space, not a browser RSS guarantee'}));
 console.log('PASS: eight 64MiB transfers across two jobs keep at most two complete bodies active through upload; exact SHA-256');
})().catch(e=>{console.error(e);process.exitCode=1;});
