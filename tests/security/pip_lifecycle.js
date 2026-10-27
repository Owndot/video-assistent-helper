'use strict';
// Executes unchanged production sections with explicit browser/Helper API models.
const fs=require('node:fs'),path=require('node:path'),vm=require('node:vm'),assert=require('node:assert/strict');
const defaultRoot=fs.existsSync(path.join(__dirname,'../src'))?path.resolve(__dirname,'..'):path.resolve(__dirname,'../..');
const root=process.argv.find((a,i)=>i>=2&&!a.startsWith('--'))||defaultRoot;
const selected=(process.argv.find(a=>a.startsWith('--case='))||'--case=all').split('=')[1];
const source=n=>fs.readFileSync(path.join(root,'src',n),'utf8');
class Target {
  constructor(){this.listeners=new Map();this.paused=false;this.duration=120;this.currentTime=20;this.playbackRate=1;this.volume=1;this.muted=false;this.pips=0;}
  addEventListener(t,f){if(!this.listeners.has(t))this.listeners.set(t,new Set());this.listeners.get(t).add(f);}
  removeEventListener(t,f){this.listeners.get(t)?.delete(f);}
  count(){return [...this.listeners.values()].reduce((n,s)=>n+s.size,0);}
  play(){this.paused=false;return Promise.resolve();} pause(){this.paused=true;}
  requestPictureInPicture(){this.pips++;return Promise.resolve();}
}
function session(){
  const handlers=new Map();const ms={metadata:null,playbackState:'none',setActionHandler(a,f){if(f)handlers.set(a,f);else handlers.delete(a);},setPositionState(){}};
  const context=vm.createContext({navigator:{mediaSession:ms},document:{pictureInPictureEnabled:true,querySelector(){return null;}},MediaMetadata:class{constructor(v){Object.assign(this,v);}},location:{href:'https://synthetic.test/'},clamp:(x,a,b)=>Math.max(a,Math.min(b,x)),pageTitle:()=>context.vp.label,pageHost:()=> 'synthetic.test',vp:null,Date,console});
  const install=vm.runInContext('(function(st,orig,vid,M,SKIP,seekBy){'+source('200-player-mediasession.js')+'})',context);
  const all=source('250-player-zurueck.js'),start=all.indexOf('    // Browser, die von selbst'),end=all.indexOf('    // ----- Im kleinen Fenster',start);assert(start>=0&&end>start,'production PiP section moved');
  const pip=vm.runInContext('(function(st,vid){'+all.slice(start,end)+'})',context);
  function open(label){const st={label,cleanup:[],pipOk:true,media:new Target(),orig:new Target()};context.vp=st;install(st,st.orig,st.media,()=>st.media,()=>10,n=>st.media.currentTime+=n);pip(st,st.media);return st;}
  const heaps=[];let failMs=false,failPip=false;
  for(let i=0;i<20;i++){
    const a=open('old-'+i),b=open('new-'+i);const play=handlers.get('play'),enter=handlers.get('enterpictureinpicture');assert.equal(handlers.size,9,'positive handler control');
    a.cleanup.forEach(f=>f()); // Actual delayed close cleanup, after production registration of successor.
    failMs ||= handlers.get('play')!==play;failPip ||= handlers.get('enterpictureinpicture')!==enter;
    if(handlers.get('play')){b.media.pause();handlers.get('play')();assert(!b.media.paused,'successor play handler did not reach media');handlers.get('pause')();assert(b.media.paused,'successor pause handler');handlers.get('enterpictureinpicture')();assert.equal(b.media.pips,1);}
    assert.equal(a.orig.count()+a.media.count(),0,'old media event listeners retained');
    context.vp=null;b.cleanup.forEach(f=>f());assert.equal(handlers.size,0,'handlers retained after final close');assert.equal(ms.metadata,null);assert.equal(ms.playbackState,'none');assert.equal(b.orig.count()+b.media.count(),0,'successor listeners retained');
    if(global.gc)global.gc();if(i%5===0)heaps.push(process.memoryUsage().heapUsed);
  }
  assert(!failMs&&!failPip,'old cleanup cleared successor handlers: media='+failMs+', pip='+failPip);
  console.log('PASS: 20 rapid cycles preserve successor MediaSession/PiP handlers, target correct media and remove owned listeners/handlers');
  console.log('MODEL-ONLY heap observations (bytes, after requested GC): '+JSON.stringify(heaps)+'; not browser RAM or proof of leak freedom');
  context.document.pictureInPictureEnabled=false;const unsupported=open('no-web-pip');assert(!handlers.has('enterpictureinpicture'),'registered unavailable Web PiP action');context.vp=null;unsupported.cleanup.forEach(f=>f());assert.equal(handlers.size,0);
}
async function brightness(){
  const text=source('140-player-ton-licht.js');
  const math=text.slice(text.indexOf('    function gammaToLin('),text.indexOf('    // Androids Standardkurve'));
  const apply=text.slice(text.indexOf('    function applyBright('),text.indexOf('    // Läuft die Helfer-App?'));
  const connect=text.slice(text.indexOf('    st.sys = null;'),text.indexOf('    let indTimer = 0;'));
  assert(math&&apply&&connect,'production brightness sections moved');
  const events=new Set(),intervals=new Map(),sent=[],requests=[];let next=1,reply={ok:true,overlay:true,write:false,vol:.5,volStep:7,volMax:15,bright:.2};
  const st={cleanup:[]},media=new Target();const ctx=vm.createContext({st,vp:st,document:{hidden:false},Date,console,Math,clamp:(x,a,b)=>Math.max(a,Math.min(b,x)),dimEl:{style:{}},vid:{style:{}},VOL:()=>media,
    helper(u){requests.push(u);return Promise.resolve(reply);},helperSend:(key,v)=>sent.push([key,v]),helperBusy:()=>false,
    setInterval(f){const id=next++;intervals.set(id,f);return id;},clearInterval(id){intervals.delete(id);},onVisChange:f=>events.add(f),offVisChange:f=>events.delete(f)});
  const api=vm.runInContext('(function(st){'+math+apply+connect+'return {applyBright};})',ctx)(st);await Promise.resolve();await Promise.resolve();
  assert.equal(intervals.size,1);assert.equal(events.size,1);api.applyBright(.7);const expected=sent.at(-1)[1];assert(expected>0&&expected<.7,'positive nonlinear Helper brightness control');
  ctx.document.hidden=true;events.forEach(f=>f());assert.equal(requests.at(-1),'/end');ctx.document.hidden=false;events.forEach(f=>f());
  const actual=sent.at(-1)[1];assert.equal(actual,expected,'background return changed linear device brightness (expected '+expected+', got '+actual+')');
  // A transient Helper outage does not mutate media playback or accumulate timers.
  reply=null;intervals.forEach(f=>f());await Promise.resolve();assert(!media.paused);reply={ok:true,vol:.3,volStep:4};intervals.forEach(f=>f());await Promise.resolve();assert.equal(st.sysK,4);assert.equal(intervals.size,1);
  ctx.vp=null;st.cleanup.forEach(f=>f());assert.equal(intervals.size,0);assert.equal(events.size,0);
  console.log('PASS: background/return sends identical linear brightness, /end on hide, bounded heartbeat survives Helper outage/restart and cleans up');
}
(async()=>{if(selected==='all'||selected==='session')session();if(selected==='all'||selected==='brightness')await brightness();})().catch(e=>{console.error(e);process.exitCode=1;});
