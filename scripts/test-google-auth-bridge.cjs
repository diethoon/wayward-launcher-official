#!/usr/bin/env node
'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { URL } = require('node:url');

const source = fs.readFileSync(
  path.join(__dirname, '../app/src/main/assets/wayward-google-auth-bridge.js'),
  'utf8'
);
const nonce = '0123456789abcdef0123456789abcdef0123456789abcdef';
const page = 'https://jeiky224.github.io/cloud/auth.html?origin=http%3A%2F%2F127.0.0.1%3A8080&nonce=' + nonce;
const expectedPage = page + '&transport=native-webview&_wml_auth_build=20261113-2';
const launched = [];
const fallback = [];
const sent = [];
let closed = false;
let received = null;
const window = {
  location: { href: 'http://127.0.0.1:8080/game/index.html' },
  WMLHost: {
    openGoogleAuthPage(url) {
      launched.push(url);
      return true;
    },
    sendGoogleAuthMessage(raw) {
      sent.push(raw);
      return true;
    },
    closeGoogleAuthPage() {
      closed = true;
    }
  },
  dispatchEvent(event) {
    received = event;
    return true;
  },
  open(url, target, features) {
    fallback.push({ url, target, features });
    return { fallback: true };
  }
};
class FakeMessageEvent {
  constructor(type, init) {
    this.type = type;
    this.data = init.data;
    this.origin = init.origin;
  }
}
vm.runInNewContext(source, { window, URL, MessageEvent: FakeMessageEvent });

const result = window.open(page, 'waywardGoogleAuth', 'popup=yes');
assert.deepEqual(launched, [expectedPage]);
assert.equal(result.closed, false);
assert.equal(typeof result.postMessage, 'function');
assert.equal(fallback.length, 0);

const hello = { type: 'wayward-cloudsave-hello', version: 1, nonce };
result.postMessage(hello, 'https://jeiky224.github.io');
assert.deepEqual(JSON.parse(sent[0]), hello);

const pageReady = { type: 'wayward-cloudsave-page-ready', version: 1, nonce };
assert.equal(window.__waywardCctReceive(JSON.stringify(pageReady)), true);
assert.equal(received.type, 'message');
assert.equal(received.origin, 'https://jeiky224.github.io');
assert.equal(received.source, result);
assert.deepEqual(JSON.parse(JSON.stringify(received.data)), pageReady);

result.close();
assert.equal(result.closed, true);
assert.equal(closed, true);

window.open('https://jeiky224.github.io/cloud/auth.html?nonce=invalid', '_blank');
assert.equal(fallback.length, 1, 'invalid nonce must not reach the native launcher');

window.open('https://example.com/', '_blank');
assert.equal(fallback.length, 2, 'unrelated popups must preserve original behavior');

const fallbackWindow = {
  location: { href: 'file:///storage/emulated/0/game/index.html' },
  open(url, target, features) {
    fallback.push({ url, target, features });
    return { fallback: true };
  }
};
vm.runInNewContext(source, { window: fallbackWindow, URL, MessageEvent: FakeMessageEvent });
const fallbackResult = fallbackWindow.open(page, '_blank');
assert.equal(fallbackResult.fallback, true, 'non-launcher environments use normal external browser opening');

assert.doesNotMatch(source, /vercel\.app|startGoogleDriveAuth|takePendingStateForNonce|window\.opener|clipboard/i);
console.log('GitHub auth in-app WebView bridge tests passed');
