(() => {
  'use strict';

  if (window.__waywardGoogleAuthBridgeInstalled) return;
  window.__waywardGoogleAuthBridgeInstalled = true;

  const AUTH_ORIGIN = 'https://jeiky224.github.io';
  const AUTH_PATH = '/cloud/auth.html';
  const MAX_MESSAGE_BYTES = 12 * 1024 * 1024;
  const CHUNK_CHARS = 24 * 1024;
  const MAX_CHUNKS = 768;
  const originalOpen = typeof window.open === 'function' ? window.open.bind(window) : null;
  const transfers = new Map();
  let activePopup = null;

  function decodeBase64Utf8(value) {
    if (typeof value !== 'string' || value.length > Math.ceil(MAX_MESSAGE_BYTES * 4 / 3) + 8) return null;
    let binary;
    try { binary = atob(value); } catch (_) { return null; }
    if (binary.length > MAX_MESSAGE_BYTES) return null;
    const bytes = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
    try { return new TextDecoder('utf-8', { fatal: true }).decode(bytes); }
    catch (_) { return null; }
  }

  function parseIncoming(raw) {
    if (typeof raw !== 'string' || raw.length > CHUNK_CHARS + 512) return null;
    let parsed;
    try { parsed = JSON.parse(raw); } catch (_) { return null; }
    if (!parsed || parsed.__waywardCctChunk !== 1) return parsed;

    if (typeof parsed.id !== 'string' || !/^[a-f0-9]{48}$/.test(parsed.id) ||
        !Number.isSafeInteger(parsed.seq) || !Number.isSafeInteger(parsed.total) ||
        parsed.total < 2 || parsed.total > MAX_CHUNKS || parsed.seq < 0 ||
        parsed.seq >= parsed.total || typeof parsed.data !== 'string' ||
        parsed.data.length > CHUNK_CHARS) return null;

    const now = Date.now();
    for (const [id, transfer] of transfers) {
      if (now - transfer.createdAt > 60000) transfers.delete(id);
    }
    let transfer = transfers.get(parsed.id);
    if (!transfer) {
      if (transfers.size >= 4) transfers.delete(transfers.keys().next().value);
      transfer = { total: parsed.total, chunks: new Array(parsed.total), chars: 0, received: 0, createdAt: now };
      transfers.set(parsed.id, transfer);
    }
    if (transfer.total !== parsed.total) { transfers.delete(parsed.id); return null; }

    if (transfer.chunks[parsed.seq] === undefined) {
      transfer.chunks[parsed.seq] = parsed.data;
      transfer.chars += parsed.data.length;
      transfer.received += 1;
    } else if (transfer.chunks[parsed.seq] !== parsed.data) {
      transfers.delete(parsed.id);
      return null;
    }
    if (transfer.chars > Math.ceil(MAX_MESSAGE_BYTES * 4 / 3) + 8) {
      transfers.delete(parsed.id);
      return null;
    }
    if (transfer.received !== transfer.total) return null;

    transfers.delete(parsed.id);
    const complete = decodeBase64Utf8(transfer.chunks.join(''));
    if (complete == null) return null;
    try { return JSON.parse(complete); } catch (_) { return null; }
  }

  function dispatchToGame(payload) {
    if (!activePopup || activePopup.closed || !payload ||
        payload.version !== 1 || payload.nonce !== activePopup.nonce) return false;
    const event = new MessageEvent('message', {
      data: payload,
      origin: AUTH_ORIGIN
    });
    Object.defineProperty(event, 'source', { configurable: false, enumerable: true, value: activePopup });
    window.dispatchEvent(event);
    return true;
  }

  window.__waywardCctReceive = function (raw) {
    if (!activePopup || activePopup.closed) return false;
    const payload = parseIncoming(raw);
    return payload ? dispatchToGame(payload) : false;
  };

  window.__waywardCctMarkClosed = function () {
    if (activePopup) activePopup.markClosed();
  };

  function makePopupProxy(host, nonce) {
    let closed = false;
    const proxy = {
      nonce,
      get closed() { return closed; },
      markClosed() { closed = true; },
      focus() {},
      close() {
        if (closed) return;
        closed = true;
        try { host.closeGoogleAuthPage(); } catch (_) {}
      },
      postMessage(message, targetOrigin) {
        if (closed) throw new Error('Google CloudSave 인증 창이 닫혔습니다.');
        if (targetOrigin !== AUTH_ORIGIN && targetOrigin !== '*') {
          throw new Error('허용되지 않은 Google CloudSave 메시지 대상입니다.');
        }
        if (!message || typeof message !== 'object' || Array.isArray(message) ||
            message.nonce !== nonce || message.version !== 1 ||
            !['wayward-cloudsave-hello', 'wayward-cloudsave-request'].includes(message.type)) {
          throw new Error('유효하지 않은 Google CloudSave 메시지입니다.');
        }
        let raw;
        try { raw = JSON.stringify(message); }
        catch (_) { throw new Error('Google CloudSave 메시지를 직렬화하지 못했습니다.'); }
        if (raw.length > MAX_MESSAGE_BYTES * 2 || !host.sendGoogleAuthMessage(raw)) {
          throw new Error('Google 인증 페이지로 메시지를 전달하지 못했습니다.');
        }
      }
    };
    return proxy;
  }

  window.open = function (url, target, features) {
    let parsed;
    try {
      parsed = new URL(String(url), window.location.href);
    } catch (_) {
      return originalOpen ? originalOpen(url, target, features) : null;
    }

    if (parsed.origin !== AUTH_ORIGIN || parsed.pathname !== AUTH_PATH) {
      return originalOpen ? originalOpen(url, target, features) : null;
    }

    const nonce = parsed.searchParams.get('nonce') || '';
    if (!/^[a-f0-9]{48}$/.test(nonce)) {
      return originalOpen ? originalOpen(url, target, features) : null;
    }

    const host = window.WMLHost;
    if (!host || typeof host.openGoogleAuthPage !== 'function' ||
        typeof host.sendGoogleAuthMessage !== 'function' ||
        typeof host.closeGoogleAuthPage !== 'function') {
      return originalOpen ? originalOpen(url, target, features) : null;
    }

    parsed.searchParams.set('transport', 'native-webview');
    try {
      if (!host.openGoogleAuthPage(parsed.href)) {
        return originalOpen ? originalOpen(url, target, features) : null;
      }
    } catch (_) {
      return originalOpen ? originalOpen(url, target, features) : null;
    }

    if (activePopup && !activePopup.closed) activePopup.markClosed();
    activePopup = makePopupProxy(host, nonce);
    return activePopup;
  };
})();
