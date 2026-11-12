(() => {
  'use strict';

  if (window.__waywardGoogleAuthBridgeInstalled) return;
  window.__waywardGoogleAuthBridgeInstalled = true;

  const AUTH_ORIGIN = 'https://wayward-cloud-auth.vercel.app';
  const originalOpen = typeof window.open === 'function' ? window.open.bind(window) : null;

  const originalFetch = typeof window.fetch === 'function' ? window.fetch.bind(window) : null;
  let lastGoogleAccessToken = '';
  if (originalFetch) {
    window.fetch = async function (input, init) {
      const requestUrl = typeof input === 'string' ? input : (input && input.url) || '';
      let requestToken = '';
      if (requestUrl.startsWith('https://www.googleapis.com/drive/v3/')) {
        try {
          const requestHeaders = new Headers(
            (init && init.headers) || (input && input.headers) || {}
          );
          const authorization = requestHeaders.get('Authorization') || '';
          const match = /^Bearer\s+(.+)$/i.exec(authorization);
          if (match) {
            requestToken = match[1];
            lastGoogleAccessToken = requestToken;
          }
        } catch (_) {}
      }

      const response = await originalFetch(input, init);
      try {
        if (response && response.status === 401 && requestToken &&
            window.WMLHost &&
            typeof window.WMLHost.clearGoogleAccessToken === 'function') {
          window.WMLHost.clearGoogleAccessToken(requestToken);
          if (lastGoogleAccessToken === requestToken) lastGoogleAccessToken = '';
        }
      } catch (_) {}
      return response;
    };
  }

  // CloudSave keeps its token inside the mod closure. Observe its actual Drive
  // request token so explicit in-mod logout clears Android's Identity cache,
  // including when the mod's session-only policy does not use localStorage.
  document.addEventListener('click', event => {
    try {
      const target = event && event.target;
      if (!target || typeof target.closest !== 'function') return;
      const root = target.closest('.wml-gd-wrap');
      if (!root) return;
      const control = target.closest(
        'button,[role="button"],input[type="button"],input[type="submit"]'
      );
      if (!control || !root.contains(control)) return;
      const label = String(control.textContent || control.value || '')
        .replace(/\s+/g, ' ').trim();
      if (label !== '로그아웃' || !lastGoogleAccessToken) return;
      const host = window.WMLHost;
      if (!host || typeof host.clearGoogleAccessToken !== 'function') return;
      host.clearGoogleAccessToken(lastGoogleAccessToken);
      lastGoogleAccessToken = '';
    } catch (_) {}
  }, true);

  window.open = function (url, target, features) {
    let parsed;
    try {
      parsed = new URL(String(url), window.location.href);
    } catch (_) {
      return originalOpen ? originalOpen(url, target, features) : null;
    }

    const host = window.WMLHost;
    if (parsed.origin !== AUTH_ORIGIN ||
        !host ||
        typeof host.startGoogleDriveAuth !== 'function' ||
        typeof host.takePendingStateForNonce !== 'function') {
      return originalOpen ? originalOpen(url, target, features) : null;
    }

    const nonce = parsed.searchParams.get('nonce') || '';
    if (!/^[a-f0-9]{48}$/.test(nonce)) {
      return originalOpen ? originalOpen(url, target, features) : null;
    }

    const parent = document.documentElement || document.body;
    if (!parent) return originalOpen ? originalOpen(url, target, features) : null;

    const frame = document.createElement('iframe');
    frame.setAttribute('aria-hidden', 'true');
    frame.style.cssText =
      'position:fixed;left:-10000px;top:-10000px;width:1px;height:1px;' +
      'border:0;opacity:0;pointer-events:none;';
    frame.src = 'about:blank';
    parent.appendChild(frame);

    const source = frame.contentWindow;
    let finished = false;
    let ticks = 0;
    let timer = 0;

    const cleanup = () => {
      if (finished) return;
      finished = true;
      if (timer) window.clearInterval(timer);
      window.setTimeout(() => {
        try { frame.remove(); } catch (_) {}
      }, 1000);
    };

    const deliver = data => {
      try {
        window.dispatchEvent(new MessageEvent('message', {
          data,
          origin: parsed.origin,
          source
        }));
      } catch (_) {}
      cleanup();
    };

    timer = window.setInterval(() => {
      if (finished) return;
      ticks += 1;
      let raw = '';
      try { raw = host.takePendingStateForNonce(nonce) || ''; } catch (_) {}

      if (raw) {
        let data;
        try { data = JSON.parse(raw); }
        catch (_) {
          data = {
            type: 'wayward-cloud-auth-error',
            nonce,
            message: 'Android 인증 결과를 읽을 수 없습니다.'
          };
        }
        if (!data || data.nonce !== nonce) {
          data = {
            type: 'wayward-cloud-auth-error',
            nonce,
            message: 'Android 인증 요청이 일치하지 않습니다.'
          };
        }
        deliver(data);
      } else if (ticks >= 720) {
        deliver({
          type: 'wayward-cloud-auth-error',
          nonce,
          message: 'Google 인증 시간이 초과되었습니다.'
        });
      }
    }, 250);

    try {
      host.startGoogleDriveAuth(nonce);
    } catch (error) {
      deliver({
        type: 'wayward-cloud-auth-error',
        nonce,
        message: String(error && error.message || error)
      });
    }

    return source;
  };
})();
