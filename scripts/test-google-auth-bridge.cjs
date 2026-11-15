#!/usr/bin/env node
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { URL } = require('node:url');

function runNativeAuthCase(source, options) {
  const nonce = options.nonce;
  const origin = 'https://wayward-cloud-auth.vercel.app';
  const frame = {
    style: {},
    contentWindow: { closed: false, close() {} },
    setAttribute() {},
    remove() { this.removed = true; }
  };
  const calls = [];
  const events = [];
  const timers = [];
  let poll = null;
  let pending = '';
  let fallbackOpened = false;

  const host = {
    takePendingStateForNonce(value) {
      if (value !== nonce || !pending) return '';
      const result = pending;
      pending = '';
      return result;
    }
  };
  if (options.policyApi) {
    host.startGoogleDriveAuthWithPolicy = (value, forceSelectAccount) => {
      calls.push(['policy', value, forceSelectAccount]);
      pending = JSON.stringify({
        type: 'wayward-cloud-auth-success',
        nonce: value,
        accessToken: 'policy-token-not-for-production',
        expiresIn: 3600
      });
    };
  }
  if (options.legacyApi) {
    host.startGoogleDriveAuth = value => {
      calls.push(['legacy', value]);
      pending = JSON.stringify({
        type: 'wayward-cloud-auth-success',
        nonce: value,
        accessToken: 'legacy-token-not-for-production',
        expiresIn: 3600
      });
    };
  }

  const window = {
    location: { href: 'https://localhost:12000/game/Wayward_WML_ko.html' },
    WMLHost: host,
    open() { fallbackOpened = true; return null; },
    setInterval(callback, ms) {
      assert.equal(ms, 250);
      poll = callback;
      return 7;
    },
    clearInterval(id) { assert.equal(id, 7); },
    setTimeout(callback) { timers.push(callback); return timers.length; },
    dispatchEvent(event) { events.push(event); return true; }
  };
  const document = {
    documentElement: { appendChild(node) { assert.equal(node, frame); } },
    createElement(tag) { assert.equal(tag, 'iframe'); return frame; }
  };
  class TestMessageEvent {
    constructor(type, init) { this.type = type; Object.assign(this, init); }
  };

  vm.runInNewContext(source, {
    window, document, URL, Headers, MessageEvent: TestMessageEvent
  });

  const query = new URLSearchParams({ nonce });
  if (options.login) query.set('login', options.login);
  const returnedWindow = window.open(origin + '/?' + query.toString(), 'wayward-cloud-auth', 'popup');
  assert.equal(returnedWindow, frame.contentWindow);
  assert.equal(fallbackOpened, false);

  if (poll && pending) poll();
  while (timers.length) timers.shift()();

  return { calls, events, returnedWindow, fallbackOpened };
}

async function testNativeAccountSelectionDispatch(source) {
  const nonce = 'a'.repeat(48);
  const forced = runNativeAuthCase(source, {
    nonce, login: 'select_account', policyApi: true, legacyApi: true
  });
  assert.deepEqual(forced.calls, [['policy', nonce, true]],
    'logout re-auth must forward forceSelectAccount=true through the policy API');
  assert.equal(forced.events.length, 1);
  assert.equal(forced.events[0].data.type, 'wayward-cloud-auth-success');
  assert.equal(forced.events[0].data.nonce, nonce);
  assert.equal(forced.events[0].source, forced.returnedWindow);

  const rememberedNonce = 'b'.repeat(48);
  const remembered = runNativeAuthCase(source, {
    nonce: rememberedNonce, login: 'remember', policyApi: true, legacyApi: true
  });
  assert.deepEqual(remembered.calls, [['policy', rememberedNonce, false]],
    'normal auth must preserve the remembered-account policy');
  assert.equal(remembered.events[0].data.nonce, rememberedNonce);

  const legacyNonce = 'c'.repeat(48);
  const legacy = runNativeAuthCase(source, {
    nonce: legacyNonce, login: 'remember', policyApi: false, legacyApi: true
  });
  assert.deepEqual(legacy.calls, [['legacy', legacyNonce]],
    'older launchers remain compatible for ordinary authentication');
  assert.equal(legacy.events[0].data.type, 'wayward-cloud-auth-success');

  const unsupportedNonce = 'd'.repeat(48);
  const unsupported = runNativeAuthCase(source, {
    nonce: unsupportedNonce, login: 'select_account', policyApi: false, legacyApi: true
  });
  assert.deepEqual(unsupported.calls, [],
    'forced account selection must not silently fall back to the legacy API');
  assert.equal(unsupported.events.length, 1);
  assert.equal(unsupported.events[0].data.type, 'wayward-cloud-auth-error');
  assert.match(unsupported.events[0].data.message, /계정 선택 API/);
  assert.equal(unsupported.events[0].source, unsupported.returnedWindow);
}

async function main() {
  const source = fs.readFileSync(
    path.join(__dirname, '../app/src/main/assets/wayward-google-auth-bridge.js'),
    'utf8'
  );

  const activitySource = fs.readFileSync(
    path.join(__dirname, '../app/src/main/java/com/wayward/launcher/MainActivity.java'),
    'utf8'
  );
  assert.ok(activitySource.includes('.setPrompt(AuthorizationRequest.Prompt.SELECT_ACCOUNT)'));
  assert.ok(activitySource.includes('clearToken(ClearTokenRequest.builder().setToken(token).build())'));

  await testNativeAccountSelectionDispatch(source);

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
  const documentListeners = {};
  const localStorageValues = new Map([
    ['wml-google-drive-session-token-v1', JSON.stringify({
      accessToken: 'persisted-token-not-for-production',
      expiresAt: Date.now() + 3600000
    })]
  ]);
  let poll = null;
  let timeout = null;
  let fallbackOpened = false;
  let pending = '';

  const window = {
    location: { href: 'https://localhost:12000/game/Wayward_WML_ko.html' },
    localStorage: {
      getItem(key) { return localStorageValues.get(key) || null; }
    },
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
    addEventListener(type, callback, capture) {
      assert.equal(type, 'click');
      assert.equal(capture, true);
      documentListeners[type] = callback;
    },
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

  const makeLogoutTarget = insideCloudSave => {
    const button = {
      textContent: '로그아웃',
      closest(selector) {
        if (selector === 'button,[role="button"]') return this;
        if (selector === '.wml-gd-wrap') return insideCloudSave ? {} : null;
        return null;
      }
    };
    return {
      closest(selector) {
        return selector === 'button,[role="button"]' ? button : null;
      }
    };
  };

  assert.equal(typeof documentListeners.click, 'function');
  documentListeners.click({ target: makeLogoutTarget(false) });
  assert.deepEqual(clearedTokens, []);
  documentListeners.click({ target: makeLogoutTarget(true) });
  assert.deepEqual(clearedTokens, ['test-token-not-for-production']);
  clearedTokens.length = 0;

  // A page reload loses the in-memory token; the persistent session token
  // must still be supplied to the native cache-clear bridge on explicit logout.
  const restoredClears = [];
  let restoredClick = null;
  const restoredWindow = {
    location: { href: 'https://localhost:12000/game/Wayward_WML_ko.html' },
    localStorage: {
      getItem(key) {
        return key === 'wml-google-drive-session-token-v1'
          ? JSON.stringify({ accessToken: 'restored-token-not-for-production' })
          : null;
      }
    },
    WMLHost: {
      clearGoogleAccessToken(value) { restoredClears.push(value); }
    },
    fetch: async () => ({ status: 200 }),
    open() { return null; }
  };
  const restoredDocument = {
    addEventListener(type, callback, capture) {
      assert.equal(type, 'click');
      assert.equal(capture, true);
      restoredClick = callback;
    },
    documentElement: {},
    createElement() { throw new Error('unexpected iframe creation'); }
  };
  vm.runInNewContext(source, {
    window: restoredWindow,
    document: restoredDocument,
    URL,
    Headers,
    MessageEvent: TestMessageEvent
  });
  restoredClick({ target: makeLogoutTarget(true) });
  assert.deepEqual(restoredClears, ['restored-token-not-for-production']);

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
