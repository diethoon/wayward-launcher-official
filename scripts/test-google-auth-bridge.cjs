#!/usr/bin/env node
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { URL } = require('node:url');

async function main() {
  const source = fs.readFileSync(
    path.join(__dirname, '../app/src/main/assets/wayward-google-auth-bridge.js'),
    'utf8'
  );

  const activitySource = fs.readFileSync(
    path.join(__dirname, '../app/src/main/java/com/wayward/launcher/MainActivity.java'),
    'utf8'
  );
  assert.match(
    activitySource,
    /\\.setPrompt\\(AuthorizationRequest\\.Prompt\\.SELECT_ACCOUNT\\)/
  );
  assert.match(
    activitySource,
    /clearToken\\(ClearTokenRequest\\.builder\\(\\)\\.setToken\\(token\\)\\.build\\(\\)\\)/
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
  const clearedTokens = [];
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
      },
      clearGoogleAccessToken(value) { clearedTokens.push(value); }
    },
    fetch: async (url) => ({ status: 401, url }),
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

  vm.runInNewContext(source, { window, document, URL, Headers, MessageEvent: TestMessageEvent });

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

  const driveResponse = await window.fetch(
    'https://www.googleapis.com/drive/v3/files',
    { headers: { Authorization: 'Bearer stale-drive-token' } }
  );
  assert.equal(driveResponse.status, 401);
  assert.deepEqual(clearedTokens, ['stale-drive-token']);

  await window.fetch('https://example.com/api', {
    headers: { Authorization: 'Bearer unrelated-token' }
  });
  assert.deepEqual(clearedTokens, ['stale-drive-token']);

  const other = window.open('https://example.com/', '_blank');
  assert.equal(other, undefined);
  assert.equal(fallbackOpened, true);

  console.log('Google native auth bridge tests passed');
}

main().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
