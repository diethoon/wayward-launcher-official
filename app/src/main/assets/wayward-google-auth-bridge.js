(() => {
  'use strict';

  if (window.__waywardGoogleAuthBridgeInstalled) return;
  window.__waywardGoogleAuthBridgeInstalled = true;

  const AUTH_ORIGINS = new Set([
    'https://jeiky224.github.io'
  ]);
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
    const hasPolicyStart = !!(host &&
      typeof host.startGoogleDriveAuthWithPolicy === 'function');
    const hasLegacyStart = !!(host &&
      typeof host.startGoogleDriveAuth === 'function');
    if (!AUTH_ORIGINS.has(parsed.origin) ||
        !host ||
        (!hasPolicyStart && !hasLegacyStart) ||
        typeof host.takePendingStateForNonce !== 'function') {
      return originalOpen ? originalOpen(url, target, features) : null;
    }

    const nonce = parsed.searchParams.get('nonce') || '';
    if (!/^[a-f0-9]{48}$/.test(nonce)) {
      return originalOpen ? originalOpen(url, target, features) : null;
    }

    // The CloudSave page communicates explicit account selection through
    // login=select_account. Missing login keeps the legacy remembered-account
    // behavior for older callers.
    const accountPolicy = parsed.searchParams.get('login') || 'remember';
    const policyIsValid = accountPolicy === 'remember' ||
      accountPolicy === 'select_account';
    const forceSelectAccount = accountPolicy === 'select_account';

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
      if (data && data.nonce === nonce &&
          data.type === 'wayward-cloud-auth-success' &&
          typeof data.accessToken === 'string' && data.accessToken) {
        lastGoogleDriveAccessToken = data.accessToken;
      }
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

    const deliverStartError = message => {
      // window.open's caller assigns the returned WindowProxy before it
      // handles postMessage. Defer errors one task so event.source matches.
      window.setTimeout(() => deliver({
        type: 'wayward-cloud-auth-error',
        nonce,
        message
      }), 0);
    };

    if (!policyIsValid) {
      deliverStartError('Google 계정 선택 정책 값이 올바르지 않습니다.');
      return source;
    }

    try {
      if (hasPolicyStart) {
        // New launchers receive both the normal and forced account policies.
        host.startGoogleDriveAuthWithPolicy(nonce, forceSelectAccount);
      } else if (!forceSelectAccount && hasLegacyStart) {
        // Older launchers remain compatible for ordinary remembered-account auth.
        host.startGoogleDriveAuth(nonce);
      } else {
        // Never silently ignore a requested account picker on an old launcher.
        deliverStartError('현재 Android 런처는 Google 계정 선택 API를 제공하지 않습니다. 런처를 업데이트한 뒤 다시 시도하세요.');
      }
    } catch (error) {
      deliverStartError(String(error && error.message || error));
    }

    return source;
  };
})();
