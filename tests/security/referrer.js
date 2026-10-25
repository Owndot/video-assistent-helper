'use strict';
const fs = require('fs'), assert = require('assert'), vm = require('vm'), path = require('path');
const root = path.join(__dirname, '../..');
const source = fs.readFileSync(path.join(root, 'src/070-extern-ytdlnis.js'), 'utf8');
const start = source.indexOf('  function mediaReferrer('), end = source.indexOf('  // Violentmonkey/Tampermonkey:', start);
assert(start >= 0 && end > start, 'missing referrer policy');
const policy = new Function('URL', 'location', 'IS_KADRI', source.slice(start, end) + ';return mediaReferrer;')(URL, {href:'https://page.test/private?PAGE_SECRET#FRAGMENT_SECRET'}, false);
const page = 'https://user:USERINFO_SECRET@page.test/private%20path?QUERY_SECRET=a%2Fb#FRAGMENT_SECRET';
assert.equal(policy('https://page.test/media?sig=SIGNED%2Ffixture', page, false), 'https://page.test/private%20path?QUERY_SECRET=a%2Fb');
for (const target of ['https://other.test/media','https://page.test:8443/media']) assert.equal(policy(target, page, false), 'https://page.test/');
assert.equal(policy('https://page.test:443/media', page, false), 'https://page.test/private%20path?QUERY_SECRET=a%2Fb');
assert.equal(policy('http://page.test/media', page, false), '');
assert.equal(policy('https://page.test/media', page, true), '', 'GM redirect ceiling must not be removed');
assert.equal(policy('https://foreign.test/media', 'http://page.test/private?SECRET=x', true), 'http://page.test/');
for (const target of ['file:///media','data:text/plain,hello','invalid']) assert.equal(policy(target, page, false), '');
assert.equal(policy('http://[::1]:8080/media','http://u:p@[::1]:8080/p?q=1#s',false),'http://[::1]:8080/p?q=1');
for (const p of ['080-helper-app.js','090-oeffnen-download.js','210-player-untertitel.js']) {
  const text = fs.readFileSync(path.join(root,'src',p),'utf8');
  assert(!/Referer:\s*location.href/.test(text), p + ': raw page header returned');
  assert(text.includes('Referer: mediaReferrer(u)'), p + ': safe header missing');
}
console.log('PASS: strict origins, ports, downgrade, raw query, IPv6 and GM redirect ceiling');
