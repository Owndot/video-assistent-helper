'use strict';
// Focused standalone-artifact check; no browser, network or runtime dependency.
const fs = require('fs'), path = require('path'), assert = require('assert'), crypto = require('crypto');
const root = path.join(__dirname, '../..');
const read = name => fs.readFileSync(path.join(root, name), 'utf8');
const sha256 = text => crypto.createHash('sha256').update(text).digest('hex');
const notices = read('vendor/hls.LICENSE');
const block = '\n/* BEGIN HLS.JS LICENSES AND ATTRIBUTIONS\n' + notices.trimEnd() +
  '\nEND HLS.JS LICENSES AND ATTRIBUTIONS */\n';
const script = read('userscript/video-assistent.user.js');
// Pin the reviewed legal text, so a truncated source notice cannot validate itself.
assert.equal(sha256(notices), 'c6e7a258ff908c20cc2fe2e6f1bf69145bbcdb291940da73a6705d097c666f3f');
for (const text of ['Dailymotion', 'Brightcove', 'DASH Industry Forum', 'vtt.js Contributors',
  'Masanao Izumo', 'Tom Jenkinson', 'Arnout Kazemier', 'Sebastian McKenzie', 'Facebook, Inc.',
  'Streaming Video Technology Alliance', 'Copyright (c) 2020 Jxck',
  'END OF TERMS AND CONDITIONS', 'APPENDIX: How to apply the Apache License']) assert(notices.includes(text), text);
function checkArtifact(text) {
  assert(text.startsWith('// ==UserScript==\n'), 'Manager metadata must remain first');
  assert.equal(text.split(block).length, 2, 'Complete readable legal block must appear exactly once');
  assert(text.indexOf(block) < text.indexOf('(function () {'), 'Legal block must precede executable body');
}
checkArtifact(script);
assert.throws(() => checkArtifact(script.replace('Dailymotion', 'REMOVED')), /legal block/);
assert.throws(() => checkArtifact(script.replace('END OF TERMS AND CONDITIONS', 'REMOVED')), /legal block/);
const expected = 'a12e7ee1cd64a69dcdb314157e45dafcba705bfb0b1440b7935cb265d374423e';
assert.equal(sha256(read('vendor/hls.min.js')), expected);
assert.equal(JSON.parse(read('vendor/hls.lock.json')).sha256, expected);
if (process.argv[2]) {
  assert.equal(script.replace(block, ''), fs.readFileSync(process.argv[2], 'utf8'),
    'License correction must leave all pre-existing artifact bytes unchanged');
  console.log('PASS: removing only the new comment restores the exact previous userscript');
}
console.log('PASS: full license/attributions, negative truncation checks and unchanged hls.js SHA-256');
