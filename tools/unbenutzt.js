#!/usr/bin/env node --expose-internals
// Sucht im fertigen Userscript nach Unbenutztem (nur Hinweise – vor dem Löschen selbst prüfen):
//   - Funktionen/Variablen, die nirgends benutzt werden
//   - CONFIG-Einträge, die nie gelesen werden
//   - Übersetzungen (EN), deren deutscher Text im Code nicht mehr vorkommt
//   - CSS-Klassen, die kein Element bekommt
// Aufruf: node --expose-internals tools/unbenutzt.js [datei]
'use strict';
const fs = require('fs');
const path = require('path');
const acorn = require('internal/deps/acorn/acorn/dist/acorn');
const walk = require('internal/deps/acorn/acorn-walk/dist/walk');

const file = process.argv[2] || path.join(__dirname, '..', 'userscript', 'video-assistent.user.js');
const code = fs.readFileSync(file, 'utf8');
const ast = acorn.parse(code, { ecmaVersion: 2022, locations: true, allowHashBang: true });
const lineOf = (n) => n.loc.start.line;

// ---- Bezeichner: Deklarationen und Benutzungen (mit Gültigkeitsbereichen) ----
const SCOPE = new Set(['Program', 'FunctionDeclaration', 'FunctionExpression', 'ArrowFunctionExpression', 'BlockStatement',
  'ForStatement', 'ForInStatement', 'ForOfStatement', 'CatchClause', 'SwitchStatement']);
const FN = new Set(['Program', 'FunctionDeclaration', 'FunctionExpression', 'ArrowFunctionExpression']);
const scopes = new Map();   // Knoten → Map(Name → Deklaration)
const declOf = new Set();   // Bezeichner-Knoten, die selbst Deklarationen sind
const decls = [];
function patternIds(p, out) {
  if (!p) return;
  if (p.type === 'Identifier') out.push(p);
  else if (p.type === 'ObjectPattern') p.properties.forEach((q) => patternIds(q.type === 'RestElement' ? q.argument : q.value, out));
  else if (p.type === 'ArrayPattern') p.elements.forEach((q) => patternIds(q, out));
  else if (p.type === 'AssignmentPattern') patternIds(p.left, out);
  else if (p.type === 'RestElement') patternIds(p.argument, out);
}
function declare(scope, id, kind, node) {
  if (!scopes.has(scope)) scopes.set(scope, new Map());
  const d = { name: id.name, line: lineOf(id), kind, node, reads: 0, writes: 0, self: 0 };
  scopes.get(scope).set(id.name, d); declOf.add(id);
  if (kind !== 'param' && kind !== 'catch' && kind !== 'fname') decls.push(d);
  return d;
}
walk.fullAncestor(ast, (n, st, anc) => {
  const up = anc.slice(0, -1).reverse();
  const nearest = (set) => up.find((a) => set.has(a.type));
  if (n.type === 'VariableDeclaration') {
    const sc = n.kind === 'var' ? nearest(FN) : nearest(SCOPE);
    n.declarations.forEach((dd) => { const ids = []; patternIds(dd.id, ids); ids.forEach((i) => declare(sc, i, n.kind, dd)); });
  }
  if (n.type === 'FunctionDeclaration' && n.id) declare(nearest(SCOPE), n.id, 'function', n);
  if (n.type === 'FunctionExpression' && n.id) declare(n, n.id, 'fname', n);
  if (FN.has(n.type) && n.params) n.params.forEach((p) => { const ids = []; patternIds(p, ids); ids.forEach((i) => declare(n, i, 'param', n)); });
  if (n.type === 'CatchClause' && n.param) { const ids = []; patternIds(n.param, ids); ids.forEach((i) => declare(n, i, 'catch', n)); }
});
walk.fullAncestor(ast, (n, st, anc) => {
  if (n.type !== 'Identifier' || declOf.has(n)) return;
  const p = anc[anc.length - 2];
  if (p && p.type === 'MemberExpression' && p.property === n && !p.computed) return;
  if (p && (p.type === 'Property' || p.type === 'MethodDefinition' || p.type === 'PropertyDefinition') && p.key === n && !p.computed && !p.shorthand) return;
  if (p && (p.type === 'LabeledStatement' || p.type === 'BreakStatement' || p.type === 'ContinueStatement')) return;
  let d = null;
  for (let k = anc.length - 1; k >= 0 && !d; k--) { const m = scopes.get(anc[k]); if (m && m.has(n.name)) d = m.get(n.name); }
  if (!d) return;   // global (window, document …)
  const write = p && ((p.type === 'AssignmentExpression' && p.left === n && p.operator === '=') ||
    (p.type === 'UpdateExpression' && anc[anc.length - 3] && anc[anc.length - 3].type === 'ExpressionStatement'));
  if (write) d.writes++; else d.reads++;
  if (anc.includes(d.node) && d.kind === 'function') d.self++;
});
const unusedIds = decls.filter((d) => d.reads - d.self <= 0);

// ---- CONFIG-Einträge ----
const cfg = [];
walk.full(ast, (n) => {
  if (n.type === 'VariableDeclarator' && n.id.name === 'CONFIG' && n.init && n.init.type === 'ObjectExpression') {
    n.init.properties.forEach((p) => cfg.push({ name: p.key.name || p.key.value, line: lineOf(p) }));
  }
});
const cfgUnused = cfg.filter((c) => !new RegExp('CONFIG\\.' + c.name + '\\b|[\'"]' + c.name + '[\'"]').test(code.replace(/^\s*\w+\s*:.*$/gm, (l) => (l.includes(c.name + ':') ? '' : l))));

// ---- Übersetzungen ----
let enStart = -1, enEnd = -1;
const enKeys = [];
walk.full(ast, (n) => {
  if (n.type === 'VariableDeclarator' && n.id.name === 'EN' && n.init && n.init.type === 'ObjectExpression') {
    enStart = n.init.start; enEnd = n.init.end;
    n.init.properties.forEach((p) => enKeys.push({ name: p.key.value, line: lineOf(p) }));
  }
});
const strs = new Set();
walk.full(ast, (n) => {
  if (n.start >= enStart && n.end <= enEnd) return;
  if (n.type === 'Literal' && typeof n.value === 'string') strs.add(n.value);
  if (n.type === 'TemplateElement') strs.add(n.value.cooked);
});
const allText = Array.from(strs).join('\u0001');
const enUnused = enKeys.filter((k) => !strs.has(k.name) && allText.indexOf(k.name) < 0 &&
  // Teile zusammengesetzter Texte („A – B“) gelten als benutzt, wenn der Teil vorkommt
  !Array.from(strs).some((s) => s.split(' – ').includes(k.name)));

// ---- CSS-Klassen ----
const css = [];
walk.full(ast, (n) => { if (n.type === 'TemplateLiteral' && n.quasis.length === 1 && /\{[^}]*:[^}]*\}/.test(n.quasis[0].value.cooked)) css.push(n.quasis[0].value.cooked); });
const cssClasses = new Set();
css.forEach((c) => (c.replace(/\{[^}]*\}/g, '{}').match(/\.[a-zA-Z][\w-]*/g) || []).forEach((x) => cssClasses.add(x.slice(1))));
const nonCss = code.replace(/`[^`]*\{[^`]*:[^`]*\}[^`]*`/g, '');
const cssUnused = Array.from(cssClasses).filter((c) => !new RegExp('[\'"\\s.]' + c.replace(/-/g, '\\-') + '[\'"\\s.)=:,]').test(nonCss) && !nonCss.includes("'" + c + "'"));

const out = (t, list, f) => { console.log('\n' + t + ' (' + list.length + ')'); list.forEach((x) => console.log('  ' + f(x))); };
out('Unbenutzte Funktionen/Variablen (nie gelesen)', unusedIds, (d) => 'Zeile ' + d.line + ': ' + d.kind + ' ' + d.name + (d.writes ? ' (nur beschrieben: ' + d.writes + '×)' : '') + (d.self ? ' (ruft nur sich selbst)' : ''));
out('CONFIG-Einträge ohne Leser', cfgUnused, (c) => 'Zeile ' + c.line + ': ' + c.name);
out('Übersetzungen ohne deutschen Text im Code', enUnused, (k) => 'Zeile ' + k.line + ': ' + JSON.stringify(k.name));
out('CSS-Klassen ohne Element', cssUnused, (c) => '.' + c);
