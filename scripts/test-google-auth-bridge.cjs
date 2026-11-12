#!/usr/bin/env node
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
const page = 'https://jeiky224.github.io/cloud/?nonce=' + nonce;
const launched = [];
const fallback = [];
const window = {
  location: { href: 'http://127.0.0.1:8080/game/index.html' },
  WMLHost: {
    openGoogleAuthPage(url) {
      launched.push(url);
      return true;
    }
  },
  open(url, target, features) {
    fallback.push({ url, target, features });
    return { fallback: true };
  }
};
vm.runInNewContext(source, { window, URL });

const result = window.open(page, 'waywardGoogleAuth', 'popup=yes');
assert.deepEqual(launched, [page]);
assert.equal(result.closed, false);
assert.equal(fallback.length, 0);

window.open('https://jeiky224.github.io/cloud/?nonce=invalid', '_blank');
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
vm.runInNewContext(source, { window: fallbackWindow, URL });
const fallbackResult = fallbackWindow.open(page, '_blank');
assert.equal(fallbackResult.fallback, true, 'non-launcher environments use normal external browser opening');

assert.doesNotMatch(source, /vercel\.app|startGoogleDriveAuth|takePendingStateForNonce|window\.opener|clipboard/i);
console.log('GitHub auth external-browser bridge tests passed');
