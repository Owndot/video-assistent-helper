// Residual-risk evidence: actual helper() code, Node GM transport adapter, synthetic data only.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const http = require('node:http');
const source = fs.readFileSync(process.argv[2] || 'src/080-helper-app.js', 'utf8');
const start = source.indexOf('  const HELPER =');
const end = source.indexOf('  // ---------- Laden über den Browser', start);
assert(start >= 0 && end > start, 'Actual helper request function not found');
const key = '0123456789abcdef0123456789abcdef'; // synthetic only
const seen = [];
const server = http.createServer((req, res) => {
  const chunks = [];
  req.on('data', c => chunks.push(c));
  req.on('end', () => {
    seen.push({ method: req.method, url: req.url, key: req.headers['x-evp-key'], body: Buffer.concat(chunks) });
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end('{"ok":true}'); // A foreign listener can impersonate /ping and other success replies.
  });
});
function gmXhr() {
  return opts => {
    const req = http.request(opts.url, { method: opts.method, headers: opts.headers }, res => {
      const chunks = [];
      res.on('data', c => chunks.push(c));
      res.on('end', () => opts.onload({ status: res.statusCode, responseText: Buffer.concat(chunks).toString() }));
    });
    req.on('error', opts.onerror);
    req.setTimeout(opts.timeout, () => { opts.ontimeout(); req.destroy(); });
    if (opts.data) req.write(Buffer.from(opts.data));
    req.end();
  };
}
(async () => {
  // Never evict an existing process. A busy fixed port fails this isolated test.
  await new Promise((resolve, reject) => { server.once('error', reject); server.listen(47811, '127.0.0.1', resolve); });
  try {
    const helper = new Function('CONFIG', 'GM_getValue', 'gmXhr', 'setTimeout', source.slice(start, end) + '\nreturn helper;')(
      { systemHelper: true }, name => { assert.equal(name, 'helperKey'); return key; }, gmXhr, setTimeout);
    assert.deepEqual(await helper('/ping', 1000), { ok: true });
    const url = 'https://media.invalid/synthetic.mp4?sig=SYNTHETIC-SIGNATURE';
    const path = '/download?u=' + encodeURIComponent(url) + '&c=SYNTHETIC-COOKIE&ref=SYNTHETIC-REF';
    assert.deepEqual(await helper(path, 1000), { ok: true });
    const body = new TextEncoder().encode('SYNTHETIC-MEDIA-DATA');
    assert.deepEqual(await helper('/push?job=synthetic&id=1', 1000, body.buffer), { ok: true });
    assert.equal(seen.length, 3);
    for (const req of seen) assert.equal(req.key, key);
    assert.equal(seen[1].url, path);
    assert.equal(seen[2].method, 'POST');
    assert.deepEqual(seen[2].body, Buffer.from(body));
    // Existing user opt-out prevents the request in this function.
    const disabled = new Function('CONFIG', 'GM_getValue', 'gmXhr', 'setTimeout', source.slice(start, end) + '\nreturn helper;')(
      { systemHelper: false }, () => key, () => { throw Error('Disabled helper reached transport'); }, setTimeout);
    assert.equal(await disabled('/ping', 1000), null);
    assert.equal(seen.length, 3);
    console.log('PASS: foreign loopback listener receives synthetic cached key/query/body; client accepts forged success; helper opt-out sends nothing');
  } finally { await new Promise(resolve => server.close(resolve)); }
})().catch(error => { console.error(error); process.exitCode = 1; });
