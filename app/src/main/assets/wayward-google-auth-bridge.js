(() => {
  'use strict';

  if (window.__waywardGoogleAuthBridgeInstalled) return;
  window.__waywardGoogleAuthBridgeInstalled = true;

  const AUTH_ORIGIN = 'https://wayward-cloud-auth.vercel.app';

  const DRIVE_SESSION_TOKEN_KEY = 'wml-google-drive-session-token-v1';
  let lastGoogleDriveAccessToken = '';

  function storedGoogleDriveAccessToken() {
    try {
      const raw = window.localStorage && window.localStorage.getItem(DRIVE_SESSION_TOKEN_KEY);
      if (!raw) return '';
      const session = JSON.parse(raw);
      return session && typeof session.accessToken === 'string' ? session.accessToken : '';
    } catch (_) {
      return '';
    }
  }

  function clearGoogleTokenOnExplicitLogout(event) {
    try {
      const target = event && event.target;
      const button = target && typeof target.closest === 'function'
        ? target.closest('button,[role="button"]') : null;
      if (!button) return;

      const label = String(button.textContent || button.innerText || '')
        .replace(/\\s+/g, ' ').trim();
      if (label !== '로그아웃') return;
      if (typeof button.closest !== 'function' || !button.closest('.wml-gd-wrap')) return;

      const token = lastGoogleDriveAccessToken || storedGoogleDriveAccessToken();
      const host = window.WMLHost;
      if (!token || !host || typeof host.clearGoogleAccessToken !== 'function') return;

      host.clearGoogleAccessToken(token);
      lastGoogleDriveAccessToken = '';
    } catch (_) {}
  }

  if (document && typeof document.addEventListener === 'function') {
    document.addEventListener('click', clearGoogleTokenOnExplicitLogout, true);
  }
  const originalOpen = typeof window.open === 'function' ? window.open.bind(window) : null;

  const originalFetch = typeof window.fetch === 'function' ? window.fetch.bind(window) : null;
  if (originalFetch) {
    window.fetch = async function (input, init) {
      const response = await originalFetch(input, init);
      try {
        const requestUrl = typeof input === 'string' ? input : (input && input.url) || '';
        if (response && response.status === 401 &&
            requestUrl.startsWith('https://www.googleapis.com/drive/v3/') &&
            window.WMLHost &&
            typeof window.WMLHost.clearGoogleAccessToken === 'function') {
          const requestHeaders = new Headers(
            (init && init.headers) || (input && input.headers) || {}
          );
          const authorization = requestHeaders.get('Authorization') || '';
          const match = /^Bearer\s+(.+)$/i.exec(authorization);
          if (match) window.WMLHost.clearGoogleAccessToken(match[1]);
        }
      } catch (_) {}
      return response;
    };
  }

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
        if (data && data.nonce === nonce &&
            data.type === 'wayward-cloud-auth-success' &&
            typeof data.accessToken === 'string' && data.accessToken) {
          lastGoogleDriveAccessToken = data.accessToken;
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
