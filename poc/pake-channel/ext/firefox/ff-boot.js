'use strict';
// Firefox adapter only (PoC): WebDriver may not navigate to moz-extension://
// pages, so the extension opens its own test bridge tab once at startup.
browser.tabs.create({ url: browser.runtime.getURL('test.html') });
