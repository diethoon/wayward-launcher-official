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
const origin = 'https://wayward-cloud-auth.vercel.app';
const frame = {
  style: {},
  contentWindow: { closed: false, close() {} },
  setAttribute() {},
  remove() { this.removed = true; }
};
const dispatched = [];
let poll = null;
let timeout = null;
let fallbackOpened = false;
let pending = '';

const window = {
  location: { href: 'https://localhost:12000/game/Wayward_WML_ko.html' },
  WMLHost: {
    startGoogleDriveAuth(value) {
      assert.equal(value, nonce);
      pending = JSON.stringify({
        type: 'wayward-cloud-auth-success',
        nonce,
        accessToken: 'test-token-not-for-production',
        expiresIn: 3600
      });
    },
    takePendingStateForNonce(value) {
      if (value !== nonce || !pending) return '';
      const valueToReturn = pending;
      pending = '';
      return valueToReturn;
    }
  },
  open() { fallbackOpened = true; },
  setInterval(callback, ms) { assert.equal(ms, 250); poll = callback; return 7; },
  clearInterval(id) { assert.equal(id, 7); },
  setTimeout(callback, ms) { timeout = callback; return 8; },
  dispatchEvent(event) { dispatched.push(event); return true; }
};
const document = {
  documentElement: { appendChild(node) { assert.equal(node, frame); } },
  createElement(tag) { assert.equal(tag, 'iframe'); return frame; }
};
class TestMessageEvent {
  constructor(type, init) { this.type = type; Object.assign(this, init); }
}

vm.runInNewContext(source, { window, document, URL, MessageEvent: TestMessageEvent });

const bridge = window.open(origin + '/?nonce=' + nonce, 'wayward-cloud-auth', 'popup');
assert.equal(bridge, frame.contentWindow);
assert.equal(fallbackOpened, false);
assert.equal(typeof poll, 'function');
poll();

assert.equal(dispatched.length, 1);
assert.equal(dispatched[0].type, 'message');
assert.equal(dispatched[0].origin, origin);
assert.equal(dispatched[0].source, bridge);
assert.equal(dispatched[0].data.type, 'wayward-cloud-auth-success');
assert.equal(dispatched[0].data.nonce, nonce);
assert.equal(dispatched[0].data.accessToken, 'test-token-not-for-production');
assert.equal(typeof timeout, 'function');

const other = window.open('https://example.com/', '_blank');
assert.equal(other, undefined);
assert.equal(fallbackOpened, true);

console.log('Google native auth bridge tests passed');
