(() => {
  'use strict';

  if (window.__waywardGoogleAuthBridgeInstalled) return;
  window.__waywardGoogleAuthBridgeInstalled = true;

  const AUTH_ORIGIN = 'https://jeiky224.github.io';
  const AUTH_PATH = '/cloud/auth.html';
  const originalOpen = typeof window.open === 'function' ? window.open.bind(window) : null;

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
    if (host && typeof host.openGoogleAuthPage === 'function') {
      try {
        if (host.openGoogleAuthPage(parsed.href)) {
          // The returned handle is only a popup-contract placeholder. The
          // authentication result returns through the nonce-bound local message event. No network relay is used.
          return { closed: false, close() { this.closed = true; } };
        }
      } catch (_) { /* preserve ordinary browser popup behavior */ }
    }
    return originalOpen ? originalOpen(url, target, features) : null;
  };
})();
