#!/usr/bin/env node
// Unit/contract tests of the actual userscript source. Not a real manager/browser test.
const fs=require('fs'),vm=require('vm'),assert=require('assert'),path=require('path');
const root=path.join(__dirname,'..');
const psl=fs.readFileSync(path.join(root,'src/065-public-suffix.js'),'utf8');
const source=fs.readFileSync(process.argv[2]||path.join(root,'src/070-extern-ytdlnis.js'),'utf8');
const first=source.indexOf('const SHARED_SUFFIX')>=0?source.indexOf('const SHARED_SUFFIX'):source.indexOf('let cachedPslBase');
const last=source.indexOf('function needsResolve',first);
assert(first>=0&&last>first,'source boundary');
const ctx=vm.createContext({});vm.runInContext(psl+'\n'+source.slice(first,last)+'\nthis.site=baseDomain;',ctx);
assert.strictEqual(ctx.site('alice.eu.org'),'alice.eu.org','foreign eu.org tenant was trusted');
assert.notStrictEqual(ctx.site('alice.github.io'),ctx.site('bob.github.io'));
assert.strictEqual(ctx.site('cdn.media.example.co.uk'),ctx.site('www.example.co.uk'));
assert.notStrictEqual(ctx.site('alice.blogspot.com'),ctx.site('bob.blogspot.com'));
assert.strictEqual(ctx.site('www.city.kawasaki.jp'),'city.kawasaki.jp');
assert.strictEqual(ctx.site('www.ck'),'www.ck');
assert.strictEqual(ctx.site('x.a.b.ck'),'a.b.ck');
assert.strictEqual(ctx.site('EXAMPLE.COM.'),'example.com');
assert.strictEqual(ctx.site('127.0.0.1'),'127.0.0.1');
assert.notStrictEqual(ctx.site('127.0.0.1'),ctx.site('127.0.0.2'));
const helper=fs.readFileSync(path.join(root,'src/080-helper-app.js'),'utf8');
const proxy=helper.slice(helper.indexOf('const proxyJobs ='),helper.indexOf('// Koppel-Seite'));
let delivered=false,anonymous=null;
Object.assign(ctx,{
 hostOf:u=>new URL(u).hostname,
 helper:async p=>p.startsWith('/pull')?(!delivered?(delivered=true,{reqs:[{u:'https://bob.eu.org/v',id:1}]}):{done:true}):{},
 gmGet:async(u,o,l,s,anon)=>{anonymous=anon;return {st:200,body:new ArrayBuffer(0)};}
});
vm.runInContext(proxy+'\nthis.run=proxyLoop;',ctx);
ctx.run('dummy',[ctx.site('alice.eu.org')]).then(()=>{
 assert.strictEqual(anonymous,true,'foreign tenant proxy request carried browser-session permission');
 const t=performance.now();for(let i=0;i<10000;i++)ctx.site('cdn.example.co.uk');
 console.log('PASS: userscript PSL and proxy credential contract; 10000 lookups ms='+(performance.now()-t).toFixed(2));
}).catch(e=>{console.error(e);process.exitCode=1;});
