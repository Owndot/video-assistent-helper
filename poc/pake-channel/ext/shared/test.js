'use strict';
// PoC-only bridge used by the automated runners in both browsers.
const api = globalThis.browser || globalThis.chrome;
window.op = (name, arg) => api.runtime.sendMessage({ t: 'test', op: name, arg });
